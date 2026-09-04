package com.jarvis.core.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device text-to-speech using Android's built-in [TextToSpeech] engine.
 *
 * Works fully offline — no API key required. The system TTS engine (Google TTS, Samsung TTS,
 * or another installed engine) handles synthesis; audio is written to a temp WAV file and
 * the raw bytes are returned for [AudioPlayer] to play.
 *
 * Initialization is retried per request: a failed engine start doesn't poison later calls.
 * [TextToSpeech] must be created on the main thread, so construction posts to the main
 * looper; [synthesize] itself runs on IO and hops to main only for engine calls.
 */
@Singleton
class AndroidTtsProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : TtsProvider {
        private companion object {
            const val TAG = "AndroidTtsProvider"
            const val UTTERANCE_ID = "jarvis_tts"
        }

        private val main = Handler(Looper.getMainLooper())

        /** Engine and its init future; both only touched on the main looper. */
        private var tts: TextToSpeech? = null
        private var initState: CompletableDeferred<Boolean>? = null

        /** Creates (or re-creates after a failure) the engine and awaits its init result. */
        private suspend fun ensureEngine(): TextToSpeech? =
            withContext(Dispatchers.Main) {
                tts?.let { return@withContext it }
                val deferred = CompletableDeferred<Boolean>()
                initState = deferred
                var created: TextToSpeech? = null
                try {
                    created = TextToSpeech(context) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
                    tts = created
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    deferred.complete(false)
                }
                if (!deferred.await()) {
                    // Failed init: drop the engine so the next request retries from scratch.
                    runCatching { created?.stop() }
                    runCatching { created?.shutdown() }
                    tts = null
                    Log.e(TAG, "TTS engine failed to initialize")
                    return@withContext null
                }
                created
            }

        override suspend fun synthesize(
            text: String,
            voice: TtsVoice,
            format: TtsFormat,
        ): Result<TtsResult> {
            val engine =
                try {
                    ensureEngine() ?: return Result.failure(
                        TtsException("Text-to-speech engine failed to initialize"),
                    )
                } catch (e: CancellationException) {
                    throw e
                }

            return withContext(Dispatchers.IO) {
                val tempFile =
                    try {
                        File.createTempFile("jarvis_tts_", ".wav", context.cacheDir)
                    } catch (t: Throwable) {
                        return@withContext Result.failure(
                            TtsException("Could not create TTS output file: ${t.message}", cause = t),
                        )
                    }
                try {
                    synthesizeToFile(engine, text, tempFile)
                } catch (e: CancellationException) {
                    runCatching { tempFile.delete() }
                    throw e
                } catch (t: Throwable) {
                    runCatching { tempFile.delete() }
                    Log.e(TAG, "TTS synthesis failed", t)
                    Result.failure(TtsException(t.message ?: "TTS synthesis failed", cause = t))
                }
            }
        }

        private suspend fun synthesizeToFile(
            engine: TextToSpeech,
            text: String,
            tempFile: File,
        ): Result<TtsResult> {
            val deferred = CompletableDeferred<Result<TtsResult>>()
            val params =
                Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
                }

            withContext(Dispatchers.Main) {
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "TTS synthesis started")
                        }

                        override fun onDone(utteranceId: String?) {
                            val audioBytes = runCatching { tempFile.readBytes() }.getOrNull()
                            runCatching { tempFile.delete() }
                            if (audioBytes != null && audioBytes.isNotEmpty()) {
                                deferred.complete(Result.success(TtsResult(audioBytes, TtsFormat.WAV)))
                            } else {
                                deferred.complete(Result.failure(TtsException("TTS produced no audio output")))
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS synthesis error")
                            runCatching { tempFile.delete() }
                            deferred.complete(Result.failure(TtsException("TTS synthesis failed")))
                        }
                    },
                )

                val resultCode = engine.synthesizeToFile(text, params, tempFile, UTTERANCE_ID)
                if (resultCode != TextToSpeech.SUCCESS) {
                    runCatching { tempFile.delete() }
                    deferred.complete(
                        Result.failure(TtsException("TTS request failed (code: $resultCode)")),
                    )
                }
            }

            return try {
                deferred.await()
            } catch (e: CancellationException) {
                runCatching { tempFile.delete() }
                // Stop the queued synthesis so it doesn't write to the deleted file.
                withContext(Dispatchers.Main) { runCatching { engine.stop() } }
                throw e
            }
        }

        /** Set the TTS language (optional — defaults to system locale). */
        fun setLanguage(locale: Locale): Boolean {
            val engine = tts ?: return false
            val result = engine.setLanguage(locale)
            return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }

        /** Stop any in-progress synthesis and release the engine. */
        fun close() {
            main.post {
                runCatching { tts?.stop() }
                runCatching { tts?.shutdown() }
                tts = null
            }
        }
    }
