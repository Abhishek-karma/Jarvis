package com.jarvis.core.voice

import com.jarvis.core.common.DispatcherProvider
import com.squareup.moshi.Moshi
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI TTS API adapter for text-to-speech (08-VOICE.md §1 TTS).
 * Uses the POST /v1/audio/speech endpoint.
 */
class OpenAiTtsProvider(
    private val baseUrl: String,
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val dispatchers: DispatcherProvider,
) : TtsProvider {

    override suspend fun synthesize(
        text: String,
        voice: TtsVoice,
        format: TtsFormat,
    ): Result<TtsResult> = withContext(dispatchers.io) {
        try {
            val bodyJson = moshi.adapter(Map::class.java).toJson(
                mapOf(
                    "model" to "tts-1",
                    "input" to text,
                    "voice" to voice.id,
                    "response_format" to format.extension,
                ),
            )

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/audio/speech")
                .header("Authorization", "Bearer ${apiKeyProvider()}")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val audioData = response.body?.bytes() ?: ByteArray(0)
                Result.success(TtsResult(audioData, format))
            } else {
                Result.failure(TtsException("TTS API error ${response.code}", response.code))
            }
        } catch (e: Exception) {
            Result.failure(TtsException(e.message ?: "TTS synthesis failed", cause = e))
        }
    }
}

class TtsException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)
