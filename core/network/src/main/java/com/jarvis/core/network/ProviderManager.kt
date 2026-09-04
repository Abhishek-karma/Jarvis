package com.jarvis.core.network

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
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
 * Owns live [OpenAiCompatibleProvider] instances — one per configured provider config.
 * Shared state lives here (a Hilt singleton), not in ViewModels, so every screen observing
 * the active provider stays in sync.
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
        private val adapters = MutableStateFlow<Map<String, OpenAiCompatibleProvider>>(emptyMap())

        /** Configured provider list (without keys) — safe to expose to any layer. */
        val providers: Flow<List<ProviderConfig>> = providerRepository.observeProviders()

        private val activeProviderId = MutableStateFlow<String?>(null)
        val active: StateFlow<String?> = activeProviderId.asStateFlow()

        fun setActiveProvider(id: String) {
            activeProviderId.value = id
        }

        /** Returns the live adapter for a config id, creating and caching one on first use. */
        fun adapterFor(providerConfig: ProviderConfig): OpenAiCompatibleProvider {
            adapters.value[providerConfig.id]?.let { return it }
            val adapter =
                OpenAiCompatibleProvider(
                    id = providerConfig.id,
                    baseUrl = providerConfig.baseUrl,
                    apiKeyProvider = { apiKeyStore.getKey(providerConfig.id) },
                    client = okHttpClient,
                    moshi = moshi,
                    dispatchers = dispatchers,
                )
            adapters.value = adapters.value + (providerConfig.id to adapter)
            return adapter
        }

        fun dropAdapter(providerId: String) {
            adapters.value[providerId]?.close()
            adapters.value = adapters.value - providerId
        }
    }
