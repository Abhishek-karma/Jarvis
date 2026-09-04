package com.jarvis.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.network.ProviderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Providers List Screen State ──────────────────────────────────────────────

data class ProvidersListState(
    val providers: List<ProviderConfig> = emptyList(),
    val isLoading: Boolean = true,
)

sealed interface ProvidersListEvent {
    data class ShowError(val message: String) : ProvidersListEvent
}

// ── Provider Edit Screen State ───────────────────────────────────────────────

data class ProviderEditState(
    val providerId: String? = null,
    val name: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val isDefault: Boolean = false,
    val isSaving: Boolean = false,
    val isVerifying: Boolean = false,
    val verificationError: String? = null,
    val verificationSuccess: Boolean = false,
    val isDeleting: Boolean = false,
    val isNew: Boolean = true,
)

sealed interface ProviderEditEvent {
    data class Saved(val providerId: String) : ProviderEditEvent
    data class Deleted(val providerId: String) : ProviderEditEvent
    data class ShowError(val message: String) : ProviderEditEvent
    data class Verified(val models: List<ModelInfo>) : ProviderEditEvent
}

// ── Combined ViewModel ───────────────────────────────────────────────────────

/**
 * Shared ViewModel for the settings flow.  Uses a sealed [SettingsRoute] to decide
 * which screen state to expose; keeps all provider CRUD in one place so the
 * ProvidersList and ProviderEdit screens can share the same coroutine scope.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val providerManager: ProviderManager,
    private val apiKeyStore: ApiKeyStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _listState = MutableStateFlow(ProvidersListState())
    val listState: StateFlow<ProvidersListState> = _listState.asStateFlow()

    private val _editState = MutableStateFlow(ProviderEditState())
    val editState: StateFlow<ProviderEditState> = _editState.asStateFlow()

    init {
        viewModelScope.launch(dispatchers.main) {
            providerRepository.observeProviders().collect { providers ->
                _listState.update { it.copy(providers = providers, isLoading = false) }
            }
        }
    }

    // ── List actions ──────────────────────────────────────────────────────────

    fun deleteProvider(id: String) {
        viewModelScope.launch(dispatchers.io) {
            providerManager.dropAdapter(id)
            providerRepository.delete(id)
            apiKeyStore.removeKey(id)
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch(dispatchers.io) {
            providerRepository.setDefault(id)
        }
    }

    // ── Edit actions ──────────────────────────────────────────────────────────

    /** Load an existing provider into the edit form. */
    fun loadProvider(id: String) {
        viewModelScope.launch(dispatchers.main) {
            val provider = providerRepository.getProvider(id) ?: return@launch
            val existingKey = apiKeyStore.getKey(id) ?: ""
            _editState.update {
                it.copy(
                    providerId = provider.id,
                    name = provider.name,
                    baseUrl = provider.baseUrl,
                    apiKey = existingKey,
                    isDefault = provider.isDefault,
                    isNew = false,
                )
            }
        }
    }

    /** Reset the edit form for a new provider. */
    fun resetForNew() {
        _editState.update {
            ProviderEditState(
                baseUrl = "https://api.openai.com/v1",
                isNew = true,
            )
        }
    }

    fun onNameChange(name: String) {
        _editState.update { it.copy(name = name, verificationError = null, verificationSuccess = false) }
    }

    fun onBaseUrlChange(url: String) {
        _editState.update { it.copy(baseUrl = url, verificationError = null, verificationSuccess = false) }
    }

    fun onApiKeyChange(key: String) {
        _editState.update { it.copy(apiKey = key, verificationError = null, verificationSuccess = false) }
    }

    fun onDefaultChange(isDefault: Boolean) {
        _editState.update { it.copy(isDefault = isDefault) }
    }

    /**
     * Verify the API key by issuing a lightweight listModels() call.
     * On success, persists the provider + key.  On failure, surfaces the HTTP status.
     */
    fun verifyAndSave() {
        val state = _editState.value
        val name = state.name.trim()
        val baseUrl = state.baseUrl.trim()
        val apiKey = state.apiKey.trim()

        if (name.isEmpty()) {
            _editState.update { it.copy(verificationError = "Name is required") }
            return
        }
        if (apiKey.isEmpty()) {
            _editState.update { it.copy(verificationError = "API key is required") }
            return
        }

        viewModelScope.launch(dispatchers.main) {
            _editState.update { it.copy(isVerifying = true, verificationError = null, verificationSuccess = false) }

            val tempId = state.providerId ?: java.util.UUID.randomUUID().toString()
            val config = ProviderConfig(id = tempId, name = name, baseUrl = baseUrl, isDefault = state.isDefault)

            // Temporarily store the key so the adapter can read it for the verify call
            apiKeyStore.putKey(tempId, apiKey)
            val adapter = providerManager.adapterFor(config)

            adapter.listModels()
                .onSuccess { models ->
                    // Persist for real
                    providerRepository.upsert(config)
                    if (state.isDefault) providerRepository.setDefault(config.id)
                    providerManager.dropAdapter(tempId) // drop temp; ProviderManager will recreate from DB
                    apiKeyStore.putKey(config.id, apiKey) // ensure key is under final ID

                    _editState.update {
                        it.copy(
                            isVerifying = false,
                            verificationSuccess = true,
                            providerId = config.id,
                            isNew = false,
                        )
                    }
                }
                .onFailure { error ->
                    providerManager.dropAdapter(tempId)
                    apiKeyStore.removeKey(tempId)
                    _editState.update {
                        it.copy(
                            isVerifying = false,
                            verificationError = error.message ?: "Verification failed",
                        )
                    }
                }
        }
    }

    fun deleteCurrentProvider() {
        val id = _editState.value.providerId ?: return
        viewModelScope.launch(dispatchers.io) {
            providerManager.dropAdapter(id)
            providerRepository.delete(id)
            apiKeyStore.removeKey(id)
        }
    }
}
