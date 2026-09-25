package com.jarvis.core.voice

/** Result of text-to-speech synthesis containing raw audio bytes and format (e.g. "wav"). */
data class TtsResult(
    val audioData: ByteArray,
    val format: String = "wav",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TtsResult) return false
        return audioData.contentEquals(other.audioData) && format == other.format
    }

    override fun hashCode(): Int = 31 * audioData.contentHashCode() + format.hashCode()
}

/**
 * Text-to-speech provider interface for on-device speech synthesis and playback.
 */
interface TtsProvider {
    /**
     * Synthesizes [text] to audio bytes.
     * Returns actual synthesized audio bytes or failure.
     * Does not trigger audio playback and does not return fake/placeholder audio.
     */
    suspend fun synthesize(text: String): Result<TtsResult>

    /**
     * Speaks [text] directly to the device audio output.
     * Completes when speech finishes or fails.
     */
    suspend fun speak(text: String): Result<Unit> = Result.success(Unit)

    /** Stops any in-progress synthesis or direct speech. */
    fun stop() {}

    /** Shuts down the TTS engine and frees resources. */
    fun close() {}
}

/** Exception thrown or returned on TTS errors. */
class TtsException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
