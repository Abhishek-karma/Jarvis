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

/**
 * Speech-to-text provider interface.
 *
 * Implementations come in two flavors:
 *  - **Buffer STT** (e.g. OpenAI Whisper, on-device file transcription): consumes audio
 *    captured by [AudioRecorder] through [transcribe].
 *  - **Live STT** (system [android.speech.SpeechRecognizer]): streams from the microphone
 *    directly, surfaced via [LiveSttSession] — [transcribe] then reports unsupported.
 */
interface SttProvider {
    /**
     * Transcribe recorded audio. [audioData] is the raw audio bytes (WAV when produced by
     * [AudioRecorder.stop]), [format] indicates the encoding.
     */
    suspend fun transcribe(
        audioData: ByteArray,
        format: AudioFormat,
    ): Result<TranscriptionResult>

    /**
     * Opens a live recognition session against the microphone. Returns null when this
     * provider only supports buffer transcription ([transcribe]).
     *
     * The caller owns the returned session and must [LiveSttSession.close] it.
     */
    fun startLiveSession(): LiveSttSession? = null
}

/**
 * One live mic recognition session. Callbacks fire on the provider's threads; the owner
 * must eventually call [close] to release the recognizer.
 */
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
