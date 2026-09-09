package com.jarvis.core.voice

/** Audio encodings accepted by [SttProvider.transcribe]. */
enum class AudioFormat(
    val mimeType: String,
    val extension: String,
) {
    WAV("audio/wav", "wav"),
    MP3("audio/mpeg", "mp3"),
}

/** Result of speech-to-text transcription. */
data class TranscriptionResult(
    val text: String,
    val language: String? = null,
    val duration: Double? = null,
)


interface SttProvider {

    suspend fun transcribe(
        audioData: ByteArray,
        format: AudioFormat,
    ): Result<TranscriptionResult>


    fun startLiveSession(): LiveSttSession? = null
}


interface LiveSttSession {
    /** Begins listening. A prior session part is delivered via [onPartial]. */
    fun startListening(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    )

    /** Stops listening without releasing the recognizer; results already produced are kept. */
    fun stopListening()

    /** Releases the recognizer. Safe to call more than once. */
    fun close()
}

/** Wraps a provider message as a typed failure, with an optional HTTP-style code. */
class SttException(
    message: String,
    val code: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)
