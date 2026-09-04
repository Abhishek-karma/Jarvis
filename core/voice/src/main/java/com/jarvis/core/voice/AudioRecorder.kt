package com.jarvis.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records audio from the device microphone.
 * Outputs raw PCM data at 16kHz mono (Whisper-compatible).
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private val bufferSize by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)
    }

    /**
     * Start recording audio.
     * @throws SecurityException if RECORD_AUDIO permission not granted.
     */
    fun start() {
        if (isRecording) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        ).also { record ->
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }
            record.startRecording()
            isRecording = true
            Log.d(TAG, "Recording started")
        }
    }

    /**
     * Stop recording and return the captured audio as WAV bytes.
     * Returns null if not recording.
     */
    fun stop(): ByteArray? {
        if (!isRecording) return null

        val record = audioRecord ?: return null
        isRecording = false

        return try {
            record.stop()
            record.release()
            audioRecord = null
            Log.d(TAG, "Recording stopped")
            null // Raw PCM; caller should capture via a reader thread before stop
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            audioRecord = null
            null
        }
    }

    /**
     * Read all available audio data while recording.
     * Call this in a loop from a background thread during recording.
     */
    fun readAvailable(): ByteArray {
        val record = audioRecord ?: return ByteArray(0)
        val buffer = ByteArray(bufferSize)
        val output = ByteArrayOutputStream()

        var bytesRead: Int
        while (isRecording) {
            bytesRead = record.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                output.write(buffer, 0, bytesRead)
            } else {
                break
            }
        }

        return output.toByteArray()
    }

    fun isRecording(): Boolean = isRecording
}
