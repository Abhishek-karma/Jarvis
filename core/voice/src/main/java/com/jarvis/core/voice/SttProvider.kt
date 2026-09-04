package com.jarvis.core.voice

import okhttp3.RequestBody

/** Audio formats supported by STT providers. */
enum class AudioFormat(val mimeType: String, val extension: String) {
    WAV("audio/wav", "wav"),
    MP3("audio/mpeg", "mp3"),
    OGG("audio/ogg", "ogg"),
    WEBM("audio/webm", "webm"),
}

/** Result of a speech-to-text transcription. */
data class TranscriptionResult(
    val text: String,
    val language: String? = null,
    val duration: Double? = null,
)

/** Speech-to-text provider interface. */
interface SttProvider {
    /** Transcribe an audio file. [audioData] is the raw audio bytes, [format] indicates the encoding. */
    suspend fun transcribe(audioData: ByteArray, format: AudioFormat): Result<TranscriptionResult>
}
