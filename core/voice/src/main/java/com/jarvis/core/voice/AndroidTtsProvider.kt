package com.jarvis.core.voice

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidTtsProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : TtsProvider {
        private companion object {
            const val TAG = "AndroidTtsProvider"
        }

        private val main = Handler(Looper.getMainLooper())
        private val initMutex = Mutex()

        private var tts: TextToSpeech? = null

        private data class SynthesisTask(
            val tempFile: File,
            val pfd: ParcelFileDescriptor?,
            val deferred: CompletableDeferred<Result<TtsResult>>,
        )

        private val activeSynthesisTasks = ConcurrentHashMap<String, SynthesisTask>()
        private val activeSpeakTasks = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()

        private val sharedProgressListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS utterance started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null) return
                Log.d(TAG, "TTS utterance done: $utteranceId")

                val speakDeferred = activeSpeakTasks.remove(utteranceId)
                if (speakDeferred != null) {
                    speakDeferred.complete(Result.success(Unit))
                    return
                }

                val task = activeSynthesisTasks.remove(utteranceId) ?: return
                runCatching { task.pfd?.close() }
                val audioBytes = runCatching { task.tempFile.readBytes() }.getOrNull()
                runCatching { task.tempFile.delete() }

                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    Log.d(TAG, "TTS synthesizeToFile produced ${audioBytes.size} bytes for $utteranceId")
                    task.deferred.complete(Result.success(TtsResult(audioBytes, "wav")))
                } else {
                    Log.w(TAG, "TTS synthesizeToFile produced empty output for $utteranceId")
                    task.deferred.complete(Result.failure(TtsException("TTS produced no audio output")))
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null) return
                Log.w(TAG, "TTS utterance error (code: $errorCode): $utteranceId")

                val speakDeferred = activeSpeakTasks.remove(utteranceId)
                if (speakDeferred != null) {
                    speakDeferred.complete(Result.failure(TtsException("TTS speak error code: $errorCode")))
                    return
                }

                val task = activeSynthesisTasks.remove(utteranceId) ?: return
                runCatching { task.pfd?.close() }
                runCatching { task.tempFile.delete() }
                task.deferred.complete(Result.failure(TtsException("TTS synthesis error code: $errorCode")))
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == null) return
                Log.d(TAG, "TTS utterance stopped: $utteranceId, interrupted=$interrupted")

                val speakDeferred = activeSpeakTasks.remove(utteranceId)
                if (speakDeferred != null) {
                    speakDeferred.complete(Result.failure(CancellationException("TTS speak cancelled")))
                    return
                }

                val task = activeSynthesisTasks.remove(utteranceId) ?: return
                runCatching { task.pfd?.close() }
                runCatching { task.tempFile.delete() }
                task.deferred.complete(Result.failure(CancellationException("TTS utterance cancelled")))
            }
        }

        private suspend fun ensureEngine(): TextToSpeech? = initMutex.withLock {
            tts?.let { return@withLock it }

            val deferred = CompletableDeferred<Boolean>()
            var created: TextToSpeech? = null

            withContext(Dispatchers.Main) {
                try {
                    created = TextToSpeech(context) { status ->
                        Log.d(TAG, "TextToSpeech onInit callback status=$status")
                        deferred.complete(status == TextToSpeech.SUCCESS)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "TextToSpeech constructor failed", t)
                    deferred.complete(false)
                }
            }

            val initialized = try {
                deferred.await()
            } catch (t: Throwable) {
                false
            }

            if (!initialized || created == null) {
                withContext(Dispatchers.Main) {
                    runCatching { created?.stop() }
                    runCatching { created?.shutdown() }
                }
                Log.e(TAG, "TTS engine failed to initialize")
                return@withLock null
            }

            withContext(Dispatchers.Main) {
                created?.let { engine ->
                    engine.setOnUtteranceProgressListener(sharedProgressListener)
                    configureOptimalVoice(engine)
                }
            }

            tts = created
            return@withLock created
        }

        private fun configureOptimalVoice(engine: TextToSpeech) {
            runCatching {
                val locale = Locale.getDefault()
                val langResult = engine.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setPitch(1.0f)
                engine.setSpeechRate(1.02f)

                val voices = engine.voices
                if (!voices.isNullOrEmpty()) {
                    val currentLang = engine.voice?.locale?.language ?: Locale.getDefault().language
                    val bestVoice = voices
                        .filter { it.locale.language.equals(currentLang, ignoreCase = true) }
                        .filter { !it.isNetworkConnectionRequired }
                        .filter { voice ->
                            voice.features == null || !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                        }
                        .maxByOrNull { voice ->
                            var score = 0
                            if (voice.quality >= Voice.QUALITY_HIGH) score += 10
                            if (voice.quality >= Voice.QUALITY_NORMAL) score += 5
                            if (voice.latency == Voice.LATENCY_VERY_LOW) score += 5
                            if (voice.latency == Voice.LATENCY_LOW) score += 3
                            
                            val nameLower = voice.name.lowercase(Locale.US)
                            if (nameLower.contains("male") || nameLower.contains("-m-") || nameLower.contains("_m_")) {
                                score += 30 // High boost for preferred male voice
                            }
                            score
                        }
                    if (bestVoice != null) {
                        engine.voice = bestVoice
                        Log.i(TAG, "Selected on-device voice: ${bestVoice.name}")
                    }
                }
            }.onFailure { Log.w(TAG, "Failed configuring optimal voice: ${it.message}") }
        }

        override suspend fun speak(text: String): Result<Unit> {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) return Result.success(Unit)

            val engine = try {
                ensureEngine() ?: return Result.failure(
                    TtsException("Text-to-speech engine failed to initialize"),
                )
            } catch (e: CancellationException) {
                throw e
            }

            val utteranceId = "speak_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            val deferred = CompletableDeferred<Result<Unit>>()
            activeSpeakTasks[utteranceId] = deferred

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            withContext(Dispatchers.Main) {
                val res = engine.speak(cleanText, TextToSpeech.QUEUE_ADD, params, utteranceId)
                if (res != TextToSpeech.SUCCESS) {
                    activeSpeakTasks.remove(utteranceId)
                    deferred.complete(Result.failure(TtsException("TTS speak failed with code $res")))
                }
            }

            return try {
                deferred.await()
            } catch (e: CancellationException) {
                activeSpeakTasks.remove(utteranceId)
                withContext(Dispatchers.Main) {
                    runCatching { engine.stop() }
                }
                throw e
            }
        }

        override suspend fun synthesize(text: String): Result<TtsResult> {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) {
                return Result.failure(TtsException("Empty text for TTS"))
            }

            val engine = try {
                ensureEngine() ?: return Result.failure(
                    TtsException("Text-to-speech engine failed to initialize"),
                )
            } catch (e: CancellationException) {
                throw e
            }

            return synthesizeWithEngine(engine, cleanText)
        }

        private suspend fun synthesizeWithEngine(
            engine: TextToSpeech,
            cleanText: String,
        ): Result<TtsResult> = withContext(Dispatchers.IO) {
            val tempFile = try {
                File.createTempFile("jarvis_tts_", ".wav", context.cacheDir)
            } catch (t: Throwable) {
                return@withContext Result.failure(
                    TtsException("Could not create TTS output file: ${t.message}", cause = t),
                )
            }

            val pfd: ParcelFileDescriptor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    ParcelFileDescriptor.open(
                        tempFile,
                        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open ParcelFileDescriptor, falling back to File: ${e.message}")
                    null
                }
            } else {
                null
            }

            val utteranceId = "tts_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            val deferred = CompletableDeferred<Result<TtsResult>>()
            activeSynthesisTasks[utteranceId] = SynthesisTask(tempFile, pfd, deferred)

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            withContext(Dispatchers.Main) {
                val resultCode = if (pfd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    engine.synthesizeToFile(cleanText, params, pfd, utteranceId)
                } else {
                    tempFile.setReadable(true, false)
                    tempFile.setWritable(true, false)
                    engine.synthesizeToFile(cleanText, params, tempFile, utteranceId)
                }

                if (resultCode != TextToSpeech.SUCCESS) {
                    activeSynthesisTasks.remove(utteranceId)
                    runCatching { pfd?.close() }
                    runCatching { tempFile.delete() }
                    deferred.complete(
                        Result.failure(TtsException("TTS synthesizeToFile failed with code $resultCode")),
                    )
                }
            }

            try {
                deferred.await()
            } catch (e: CancellationException) {
                activeSynthesisTasks.remove(utteranceId)
                runCatching { pfd?.close() }
                runCatching { tempFile.delete() }
                withContext(Dispatchers.Main) {
                    runCatching { engine.stop() }
                }
                throw e
            }
        }

        override fun stop() {
            main.post {
                activeSynthesisTasks.forEach { (_, task) ->
                    runCatching { task.pfd?.close() }
                    runCatching { task.tempFile.delete() }
                    task.deferred.cancel()
                }
                activeSynthesisTasks.clear()

                activeSpeakTasks.forEach { (_, deferred) ->
                    deferred.cancel()
                }
                activeSpeakTasks.clear()

                runCatching { tts?.stop() }
            }
        }

        override fun close() {
            main.post {
                activeSynthesisTasks.forEach { (_, task) ->
                    runCatching { task.pfd?.close() }
                    runCatching { task.tempFile.delete() }
                    task.deferred.cancel()
                }
                activeSynthesisTasks.clear()

                activeSpeakTasks.forEach { (_, deferred) ->
                    deferred.cancel()
                }
                activeSpeakTasks.clear()

                runCatching { tts?.stop() }
                runCatching { tts?.shutdown() }
                tts = null
            }
        }
    }
