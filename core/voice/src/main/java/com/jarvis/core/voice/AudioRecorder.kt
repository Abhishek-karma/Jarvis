package com.jarvis.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records audio from the device microphone into an in-memory buffer.
 * Outputs 16-bit PCM at 16 kHz mono, wrapped as WAV by [stop] (Whisper-compatible).
 */
@Singleton
class AudioRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "AudioRecorder"
            internal const val SAMPLE_RATE = 16000
            internal const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
            internal const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

            /** 16 kHz · 16-bit · mono → 32,000 bytes per second. */
            private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
            private const val MAX_CAPTURE_SECONDS = 120
        }

        private val lock = Any()

        private var audioRecord: android.media.AudioRecord? = null

        @Volatile private var isRecording = false

        /** Captured PCM bytes drained by the reader coroutine inside [start]'s scope. */
        private val captured = ByteArrayOutputStream()

        private val bufferSize by lazy {
            android.media.AudioRecord
                .getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                .coerceAtLeast(4096)
        }

        /**
         * Starts recording and drains the mic on a dedicated thread, so [stop] returns the full
         * capture. Blocks until the recorder is live (or fails).
         * @throws SecurityException if RECORD_AUDIO permission is not granted.
         * @throws IllegalStateException if the native recorder fails to initialize.
         */
        fun start(readerScope: kotlinx.coroutines.CoroutineScope? = null) {
            synchronized(lock) {
                if (isRecording) return
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("RECORD_AUDIO permission not granted")
                }
                if (captured.size() > 0) captured.reset()

                val record =
                    try {
                        android.media.AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSize,
                        )
                    } catch (e: Exception) {
                        throw IllegalStateException("Failed to create AudioRecord: ${e.message}", e)
                    }
                if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    throw IllegalStateException("AudioRecord failed to initialize")
                }
                record.startRecording()
                audioRecord = record
                isRecording = true
                Log.d(TAG, "Recording started")
            }

            // Drain the mic on a background thread so the buffer never overruns while recording.
            Thread {
                val buffer = ByteArray(bufferSize)
                try {
                    while (isRecording) {
                        val read = synchronized(lock) { audioRecord }?.read(buffer, 0, buffer.size) ?: break
                        if (read > 0) {
                            synchronized(captured) { captured.write(buffer, 0, read) }
                            if (captured.size() >= MAX_CAPTURE_SECONDS * BYTES_PER_SECOND) {
                                Log.w(TAG, "Max capture length reached — stopping recording")
                                stop()
                                break
                            }
                        } else if (read < 0) {
                            Log.e(TAG, "AudioRecord read error: $read")
                            break
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is InterruptedException) Log.e(TAG, "Reader thread crashed", t)
                }
            }.apply {
                name = "jarvis-audio-reader"
                isDaemon = true
            }.also { it.start() }
        }

        /**
         * Stops recording and returns the captured audio as WAV bytes (header + PCM).
         * Returns null when nothing was recording or no samples were captured.
         */
        fun stop(): ByteArray? {
            val record: android.media.AudioRecord?
            synchronized(lock) {
                if (!isRecording) return null
                isRecording = false
                record = audioRecord
                audioRecord = null
            }
            try {
                record?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
            } finally {
                record?.release()
            }
            // Give the reader thread a beat to drain the last chunks into [captured].
            Thread.sleep(50)
            val pcm =
                synchronized(captured) {
                    val bytes = captured.toByteArray()
                    captured.reset()
                    bytes
                }
            if (pcm.isEmpty()) {
                Log.d(TAG, "Recording stopped with no samples")
                return null
            }
            return wavWrap(pcm)
        }

        /** True while a recording is active. */
        fun isRecording(): Boolean = isRecording

        /** Releases any recorder left behind (e.g. scope cancellation mid-recording). */
        fun cancel() {
            synchronized(lock) {
                isRecording = false
                audioRecord?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                audioRecord = null
            }
            synchronized(captured) { captured.reset() }
        }

        private fun wavWrap(pcm: ByteArray): ByteArray {
            val byteRate = SAMPLE_RATE * 2 // 16-bit mono
            val header = ByteArray(44)

            fun putInt(
                offset: Int,
                value: Int,
            ) {
                for (i in 0 until 4) header[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
            }

            fun putShort(
                offset: Int,
                value: Int,
            ) {
                for (i in 0 until 2) header[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
            }
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            putInt(4, 36 + pcm.size)
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            putInt(16, 16) // PCM chunk size
            putShort(20, 1) // audio format: PCM
            putShort(22, 1) // channels: mono
            putInt(24, SAMPLE_RATE) // sample rate
            putInt(28, byteRate) // byte rate
            putShort(32, 2) // block align
            putShort(34, 16) // bits per sample
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            putInt(40, pcm.size)
            return header + pcm
        }
    }
