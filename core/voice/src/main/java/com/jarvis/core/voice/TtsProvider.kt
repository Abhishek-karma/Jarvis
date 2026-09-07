package com.jarvis.core.voice

/** TTS voice presets (OpenAI TTS API supported voices). */
enum class TtsVoice(
    val id: String,
) {
    ALLOY("alloy"),
    ECHO("echo"),
    FABLE("fable"),
    ONYX("onyx"),
    NOVA("nova"),
    SHIMMER("shimmer"),
}

/** TTS output formats. */
enum class TtsFormat(
    val mimeType: String,
    val extension: String,
) {
    MP3("audio/mpeg", "mp3"),
    OPUS("audio/opus", "opus"),
    AAC("audio/aac", "aac"),
    FLAC("audio/flac", "flac"),
    WAV("audio/wav", "wav"),
    PCM("audio/pcm", "pcm"),
}

/** Result of text-to-speech synthesis. */
data class TtsResult(
    val audioData: ByteArray,
    val format: TtsFormat,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TtsResult) return false
        return audioData.contentEquals(other.audioData) && format == other.format
    }

    override fun hashCode(): Int = 31 * audioData.contentHashCode() + format.hashCode()
}

/** Text-to-speech provider interface. */
interface TtsProvider {
    /** Synthesize [text] to speech using [voice]. Returns audio bytes in [format]. */
    suspend fun synthesize(
        text: String,
        voice: TtsVoice = TtsVoice.NOVA,
        format: TtsFormat = TtsFormat.MP3,
    ): Result<TtsResult>
}
