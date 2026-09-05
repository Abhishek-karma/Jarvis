package com.jarvis.core.network

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.network.anthropic.AnthropicProvider
import com.jarvis.core.network.gemini.GeminiProvider
import com.jarvis.core.network.sse.OpenAiCompatibleProvider
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns live [LlmProvider] instances — one per configured provider config, dispatched by
 * [ProviderConfig.type]. Shared state lives here (a Hilt singleton), not in ViewModels, so
 * every screen observing the active provider stays in sync.
 *
 * Cache key is the (id, type) pair: re-typing an existing provider must rebuild the right
 * adapter, and dropping one evicts every family variant of that id.
 */
@Singleton
class ProviderManager
    @Inject
    constructor(
        private val providerRepository: ProviderRepository,
        private val apiKeyStore: ApiKeyStore,
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi,
        private val dispatchers: DispatcherProvider,
    ) {
        /** Keyed by "<configId>:<ProviderType>" — see the class doc. */
        private val adapters =
            MutableStateFlow<Map<String, LlmProvider>>(emptyMap())

        /** Configured provider list (without keys) — safe to expose to any layer. */
        val providers: Flow<List<ProviderConfig>> = providerRepository.observeProviders()

        private val activeProviderId = MutableStateFlow<String?>(null)
        val active: StateFlow<String?> = activeProviderId.asStateFlow()

        fun setActiveProvider(id: String) {
            activeProviderId.value = id
        }

        /** Returns the live adapter for a config, creating and caching one on first use. */
        fun adapterFor(providerConfig: ProviderConfig): LlmProvider {
            val cacheKey = "${providerConfig.id}:${providerConfig.type.name}"
            adapters.value[cacheKey]?.let { return it }
            val adapter =
                when (providerConfig.type) {
                    ProviderType.OPENAI_COMPATIBLE ->
                        OpenAiCompatibleProvider(
                            id = providerConfig.id,
                            baseUrl = providerConfig.baseUrl,
                            apiKeyProvider = { apiKeyStore.getKey(providerConfig.id) },
                            client = okHttpClient,
                            moshi = moshi,
                            dispatchers = dispatchers,
                        )

                    ProviderType.ANTHROPIC ->
                        AnthropicProvider(
                            id = providerConfig.id,
                            baseUrl = providerConfig.baseUrl,
                            apiKeyProvider = { apiKeyStore.getKey(providerConfig.id) },
                            client = okHttpClient,
                            moshi = moshi,
                            dispatchers = dispatchers,
                        )

                    ProviderType.GEMINI ->
                        GeminiProvider(
                            id = providerConfig.id,
                            baseUrl = providerConfig.baseUrl,
                            apiKeyProvider = { apiKeyStore.getKey(providerConfig.id) },
                            client = okHttpClient,
                            moshi = moshi,
                            dispatchers = dispatchers,
                        )
                }
            adapters.value = adapters.value + (cacheKey to adapter)
            return adapter
        }

        /** Closes and evicts every cached adapter variant of [providerId]. */
        fun dropAdapter(providerId: String) {
            val removed = adapters.value.filterKeys { it.startsWith("$providerId:") }
            if (removed.isEmpty()) return
            adapters.value = adapters.value - removed.keys
            removed.values.forEach { it.close() }
        }
    }
