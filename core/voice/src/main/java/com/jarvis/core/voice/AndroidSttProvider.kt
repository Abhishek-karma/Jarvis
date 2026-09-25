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
            var recognizerRef: SpeechRecognizer? = null
            main.post {
                try {
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizerRef = recognizer
                    recognizer.setRecognitionListener(
                        listenerFor(
                            onResult = { text ->
                                deferred.complete(Result.success(TranscriptionResult(text)))
                                main.post {
                                    runCatching { recognizer.destroy() }
                                    if (recognizerRef === recognizer) recognizerRef = null
                                }
                            },
                            onError = { sttError ->
                                deferred.complete(Result.failure(SttException(sttError.message)))
                                main.post {
                                    runCatching { recognizer.destroy() }
                                    if (recognizerRef === recognizer) recognizerRef = null
                                }
                            },
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
                    runCatching { recognizerRef?.destroy() }
                    recognizerRef = null
                    deferred.complete(
                        Result.failure(SttException(t.message ?: "Failed to start transcription", cause = t)),
                    )
                }
            }
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                main.post {
                    runCatching { recognizerRef?.destroy() }
                    recognizerRef = null
                }
                throw e
            } finally {
                main.post {
                    runCatching { recognizerRef?.destroy() }
                    recognizerRef = null
                    runCatching { tempFile.delete() }
                }
            }
        }

        override fun startLiveSession(): LiveSttSession = LiveSpeechSession()

        /** One live mic session bound to the main looper; [close] releases the recognizer. */
        private inner class LiveSpeechSession : LiveSttSession {
            private val main = Handler(Looper.getMainLooper())
            private var recognizer: SpeechRecognizer? = null
            private var closed = false
            private var rmsListener: ((Float) -> Unit)? = null

            override fun setRmsListener(onRmsChanged: (Float) -> Unit) {
                this.rmsListener = onRmsChanged
            }

            override fun startListening(
                onPartial: (String) -> Unit,
                onResult: (String) -> Unit,
                onError: (SttRecognitionError) -> Unit,
            ) {
                main.post {
                    if (closed) return@post
                    try {
                        val r =
                            recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                                recognizer = it
                            }
                        runCatching { r.cancel() }
                        r.setRecognitionListener(
                            listenerFor(
                                onPartial = onPartial,
                                onResult = onResult,
                                onError = onError,
                                onRms = { rms -> rmsListener?.invoke(rms) },
                            ),
                        )
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
                        onError(
                            SttRecognitionError(
                                category = SttErrorCategory.PERMANENT,
                                message = t.message ?: "Failed to start listening",
                            ),
                        )
                    }
                }
            }

            override fun stopListening() {
                main.post {
                    runCatching { recognizer?.stopListening() }
                        .onFailure { Log.w(TAG, "stopListening failed: ${it.message}") }
                }
            }

            override fun cancelListening() {
                main.post {
                    runCatching { recognizer?.cancel() }
                        .onFailure { Log.w(TAG, "cancelListening failed: ${it.message}") }
                }
            }

            override fun close() {
                closed = true
                main.post {
                    runCatching {
                        recognizer?.cancel()
                        recognizer?.destroy()
                    }.onFailure { Log.w(TAG, "destroy failed: ${it.message}") }
                    recognizer = null
                }
            }
        }

        private fun listenerFor(
            onPartial: (String) -> Unit = {},
            onResult: (String) -> Unit,
            onError: (SttRecognitionError) -> Unit,
            onRms: (Float) -> Unit = {},
        ): RecognitionListener =
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    onRms(normalized)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val (category, message) = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                            SttErrorCategory.TIMEOUT to "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            SttErrorCategory.TIMEOUT to "Speech timed out"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            SttErrorCategory.BUSY to "Recognition engine busy"
                        SpeechRecognizer.ERROR_CLIENT ->
                            SttErrorCategory.CANCELLED to "Client cancelled or client error"
                        SpeechRecognizer.ERROR_AUDIO ->
                            SttErrorCategory.TRANSIENT to "Audio recording error"
                        SpeechRecognizer.ERROR_SERVER ->
                            SttErrorCategory.TRANSIENT to "Server error"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            SttErrorCategory.TRANSIENT to "Network error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            SttErrorCategory.PERMANENT to "Microphone permission missing"
                        else ->
                            SttErrorCategory.PERMANENT to "Recognition error ($error)"
                    }
                    Log.w(TAG, "Recognition error: $message (code=$error, category=$category)")
                    onError(SttRecognitionError(category = category, message = message, code = error))
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
