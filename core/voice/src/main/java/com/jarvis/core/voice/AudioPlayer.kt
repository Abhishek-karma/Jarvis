package com.jarvis.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays back audio data from TTS.
 * Writes audio bytes to a temp file, then plays via MediaPlayer. `stop()` during playback
 * resumes the suspended [play] call — playback ends early instead of hanging forever.
 */
@Singleton
class AudioPlayer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "AudioPlayer"
        }

        private var mediaPlayer: MediaPlayer? = null

        /** Resumes [play] when [stop] tears the player down mid-playback. */
        private var activePlayback: CompletableDeferred<Unit>? = null

        /**
         * Play audio bytes. Suspends until playback completes (or is stopped).
         * [format] determines the file extension (e.g. "mp3", "wav").
         */
        suspend fun play(
            audioData: ByteArray,
            format: String = "mp3",
        ) {
            stop()

            val tempFile =
                withContext(Dispatchers.IO) {
                    File.createTempFile("jarvis_tts_", ".$format", context.cacheDir).apply {
                        FileOutputStream(this).use { it.write(audioData) }
                    }
                }

            try {
                val done = CompletableDeferred<Unit>()
                activePlayback = done
                mediaPlayer =
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build(),
                        )
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener {
                            Log.d(TAG, "Playback completed")
                            done.complete(Unit)
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "Playback error: what=$what extra=$extra")
                            done.complete(Unit)
                            true
                        }
                        prepare()
                        start()
                        Log.d(TAG, "Playback started")
                    }
                // Suspend until the completion listener or [stop] resumes us.
                done.await()
            } finally {
                activePlayback = null
                releasePlayer()
                withContext(Dispatchers.IO) { runCatching { tempFile.delete() } }
            }
        }

        /** Stop any current playback and resume the waiter immediately. */
        fun stop() {
            releasePlayer()
            // The player is gone; the completion listener will never fire — resume [play] now.
            activePlayback?.complete(Unit)
            activePlayback = null
        }

        private fun releasePlayer() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback", e)
            }
            mediaPlayer = null
        }
    }
