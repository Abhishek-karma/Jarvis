package com.jarvis.core.voice

import android.content.Context
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderType
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hybrid TTS Provider that uses OpenAI neural TTS for realistic voice synthesis
 * when an API key is configured, with automatic fallback to Android on-device TTS.
 */
@Singleton
class HybridTtsProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val apiKeyStore: ApiKeyStore,
        private val providerRepository: ProviderRepository,
        @Named("llm") private val okHttpClient: OkHttpClient,
        private val moshi: Moshi,
        private val dispatchers: DispatcherProvider,
    ) : TtsProvider {

        private val androidTtsProvider = AndroidTtsProvider(context)

        private suspend fun getOpenAiApiKey(): String? {
            // Check direct "openai" key
            val directKey = apiKeyStore.getKey("openai")
            if (!directKey.isNullOrBlank()) return directKey

            // Search configured providers for OpenAI provider key
            val providers = providerRepository.observeProviders().firstOrNull().orEmpty()
            val openAiProvider =
                providers.firstOrNull {
                    it.type == ProviderType.OPENAI_COMPATIBLE ||
                        it.baseUrl.contains("openai.com", ignoreCase = true)
                }
            if (openAiProvider != null) {
                val key = apiKeyStore.getKey(openAiProvider.id)
                if (!key.isNullOrBlank()) return key
            }

            // Check default provider key
            val defaultProvider = providers.firstOrNull { it.isDefault }
            if (defaultProvider != null) {
                val key = apiKeyStore.getKey(defaultProvider.id)
                if (!key.isNullOrBlank()) return key
            }

            return null
        }

        override suspend fun synthesize(
            text: String,
            voice: TtsVoice,
            format: TtsFormat,
        ): Result<TtsResult> {
            val apiKey = getOpenAiApiKey()
            if (!apiKey.isNullOrBlank()) {
                val openAiTts =
                    OpenAiTtsProvider(
                        baseUrl = "https://api.openai.com/v1",
                        apiKeyProvider = { apiKey },
                        client = okHttpClient,
                        moshi = moshi,
                        dispatchers = dispatchers,
                    )
                val result = openAiTts.synthesize(text, voice, format)
                if (result.isSuccess) {
                    return result
                }
            }
            return androidTtsProvider.synthesize(text, voice, format)
        }
    }
