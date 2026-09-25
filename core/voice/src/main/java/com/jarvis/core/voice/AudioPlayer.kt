package com.jarvis.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "AudioPlayer"
        }

        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        private var focusRequest: AudioFocusRequest? = null
        private var mediaPlayer: MediaPlayer? = null
        private var hasAudioFocus: Boolean = false

        /** Resumes [play] when [stop] tears the player down mid-playback. */
        private var activePlayback: CompletableDeferred<Unit>? = null

        suspend fun play(
            audioData: ByteArray,
            format: String = "mp3",
        ) {
            if (audioData.isEmpty()) {
                Log.w(TAG, "Audio data is empty, skipping playback")
                return
            }

            releasePlayer()

            // Ensure media stream volume is audible
            runCatching {
                val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
                if (currentVol == 0 && maxVol > 0) {
                    audioManager?.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        (maxVol * 0.7f).toInt().coerceAtLeast(1),
                        0,
                    )
                }
            }

            val tempFile =
                withContext(Dispatchers.IO) {
                    File.createTempFile("jarvis_tts_", ".$format", context.cacheDir).apply {
                        FileOutputStream(this).use { it.write(audioData) }
                    }
                }

            try {
                val done = CompletableDeferred<Unit>()
                activePlayback = done

                requestAudioFocus()

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
                        setVolume(1.0f, 1.0f)
                        start()
                        Log.d(TAG, "Playback started")
                    }

                done.await()
            } finally {
                activePlayback = null
                releasePlayer()
                withContext(Dispatchers.IO) { runCatching { tempFile.delete() } }
            }
        }

        /** Stop any current playback, abandon audio focus, and resume the waiter immediately. */
        fun stop() {
            releasePlayer()
            abandonAudioFocus()
            activePlayback?.complete(Unit)
            activePlayback = null
        }

        private fun requestAudioFocus() {
            if (hasAudioFocus) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val playbackAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(playbackAttributes)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener { focusChange ->
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            ) {
                                stop()
                            }
                        }
                        .build()
                    focusRequest = req
                    audioManager?.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager?.requestAudioFocus(
                        { focusChange ->
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            ) {
                                stop()
                            }
                        },
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                    )
                }
                hasAudioFocus = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request audio focus: ${e.message}")
            }
        }

        private fun abandonAudioFocus() {
            if (!hasAudioFocus) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                    focusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    audioManager?.abandonAudioFocus(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
            } finally {
                hasAudioFocus = false
            }
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

