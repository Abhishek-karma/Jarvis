package com.jarvis.core.voice

import com.jarvis.core.common.DispatcherProvider
import com.squareup.moshi.Moshi
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Whisper API adapter for speech-to-text.
 * Uses the POST /v1/audio/transcriptions endpoint with multipart upload.
 */
class OpenAiSttProvider(
    private val baseUrl: String,
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val dispatchers: DispatcherProvider,
) : SttProvider {
    @Suppress("UNCHECKED_CAST")
    override suspend fun transcribe(
        audioData: ByteArray,
        format: AudioFormat,
    ): Result<TranscriptionResult> =
        withContext(dispatchers.io) {
            try {
                val body =
                    MultipartBody
                        .Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "file",
                            "audio.${format.extension}",
                            audioData.toRequestBody(format.mimeType.toMediaType()),
                        ).addFormDataPart("model", "whisper-1")
                        .addFormDataPart("response_format", "verbose_json")
                        .build()

                val request =
                    Request
                        .Builder()
                        .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
                        .header("Authorization", "Bearer ${apiKeyProvider()}")
                        .post(body)
                        .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: ""
                    val map = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any?> ?: emptyMap()
                    Result.success(
                        TranscriptionResult(
                            text = map["text"]?.toString() ?: "",
                            language = map["language"]?.toString(),
                            duration = (map["duration"] as? Number)?.toDouble(),
                        ),
                    )
                } else {
                    Result.failure(SttException("Whisper API error ${response.code}", response.code))
                }
            } catch (e: Exception) {
                Result.failure(SttException(e.message ?: "Transcription failed", cause = e))
            }
        }
}
