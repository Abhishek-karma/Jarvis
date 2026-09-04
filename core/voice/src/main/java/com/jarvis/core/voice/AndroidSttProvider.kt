package com.jarvis.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device speech-to-text using Android's built-in [SpeechRecognizer].
 *
 * Live mic recognition is the primary path: [startLiveSession] streams from the microphone
 * through the system speech service (fully offline on devices with on-device recognition,
 * no API key). Buffer transcription ([transcribe]) is supported for WAV captures via
 * audio injection where the system service accepts it; devices that don't accept injected
 * audio fail with a clear error rather than recognizing nothing.
 *
 * [SpeechRecognizer] must be created and used on the main thread, so every entry point
 * posts to the main looper. Callers may use any dispatcher.
 */
@Singleton
class AndroidSttProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SttProvider {
        private companion object {
            const val TAG = "AndroidSttProvider"
        }

        override suspend fun transcribe(
            audioData: ByteArray,
            format: AudioFormat,
        ): Result<TranscriptionResult> {
            if (audioData.isEmpty()) {
                return Result.failure(SttException("No audio captured"))
            }
            if (format != AudioFormat.WAV) {
                return Result.failure(SttException("On-device STT only accepts WAV input, got $format"))
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                return Result.failure(SttException("Speech recognition is not available on this device"))
            }

            val tempFile =
                withContext(Dispatchers.IO) {
                    File.createTempFile("jarvis_stt_", ".wav", context.cacheDir).apply {
                        FileOutputStream(this).use { it.write(audioData) }
                    }
                }
            val deferred = CompletableDeferred<Result<TranscriptionResult>>()
            val main = Handler(Looper.getMainLooper())
            main.post {
                var recognizer: SpeechRecognizer? = null
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer.setRecognitionListener(
                        listenerFor(
                            onResult = { text -> deferred.complete(Result.success(TranscriptionResult(text))) },
                            onError = { message -> deferred.complete(Result.failure(SttException(message))) },
                        ),
                    )
                    recognizer.startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra("android.speech.extra.AUDIO_INJECTION_SOURCE", tempFile.absolutePath)
                        },
                    )
                } catch (t: Throwable) {
                    runCatching { recognizer?.destroy() }
                    deferred.complete(
                        Result.failure(SttException(t.message ?: "Failed to start transcription", cause = t)),
                    )
                }
            }
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                throw e
            } finally {
                main.post { runCatching { tempFile.delete() } }
            }
        }

        override fun startLiveSession(): LiveSttSession = LiveSpeechSession()

        /** One live mic session bound to the main looper; [close] releases the recognizer. */
        private inner class LiveSpeechSession : LiveSttSession {
            private val main = Handler(Looper.getMainLooper())
            private var recognizer: SpeechRecognizer? = null
            private var closed = false

            override fun startListening(
                onPartial: (String) -> Unit,
                onResult: (String) -> Unit,
                onError: (String) -> Unit,
            ) {
                main.post {
                    if (closed) return@post
                    try {
                        val r =
                            recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                                recognizer = it
                            }
                        r.setRecognitionListener(listenerFor(onPartial, onResult, onError))
                        r.startListening(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            },
                        )
                    } catch (t: Throwable) {
                        onError(t.message ?: "Failed to start listening")
                    }
                }
            }

            override fun stopListening() {
                main.post {
                    runCatching { recognizer?.stopListening() }
                        .onFailure { Log.w(TAG, "stopListening failed: ${it.message}") }
                }
            }

            override fun close() {
                closed = true
                main.post {
                    runCatching { recognizer?.destroy() }
                        .onFailure { Log.w(TAG, "destroy failed: ${it.message}") }
                    recognizer = null
                }
            }
        }

        private fun listenerFor(
            onPartial: (String) -> Unit = {},
            onResult: (String) -> Unit,
            onError: (String) -> Unit,
        ): RecognitionListener =
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val message =
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition engine busy"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                            else -> "Recognition error ($error)"
                        }
                    Log.e(TAG, "Recognition error: $message")
                    onError(message)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    onResult(matches?.firstOrNull().orEmpty())
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {}
            }
    }
