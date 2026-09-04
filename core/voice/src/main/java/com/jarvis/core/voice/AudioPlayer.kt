package com.jarvis.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Plays back audio data from TTS.
 * Writes audio bytes to a temp file, then plays via MediaPlayer.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null

    /**
     * Play audio bytes. Suspends until playback completes.
     * [format] determines the file extension (e.g., "mp3", "wav").
     */
    suspend fun play(audioData: ByteArray, format: String = "mp3") {
        stop()

        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("jarvis_tts_", ".$format", context.cacheDir).apply {
                FileOutputStream(this).use { it.write(audioData) }
            }
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                setDataSource(tempFile.absolutePath)
                prepare()

                suspendCancellableCoroutine { cont ->
                    setOnCompletionListener {
                        Log.d(TAG, "Playback completed")
                        cont.resume(Unit)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "Playback error: what=$what extra=$extra")
                        cont.resume(Unit)
                        true
                    }
                    start()
                    Log.d(TAG, "Playback started")
                }
            }
        } finally {
            mediaPlayer?.release()
            mediaPlayer = null
            tempFile.delete()
        }
    }

    /** Stop any current playback. */
    fun stop() {
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
