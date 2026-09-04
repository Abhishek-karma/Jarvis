package com.jarvis.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.ml.LocalModelState
import com.jarvis.core.ml.LocalModelStore
import com.jarvis.core.ml.LocalModelSpec
import com.jarvis.core.network.ProviderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProvidersListState(
    val providers: List<ProviderConfig> = emptyList(),
    val isLoading: Boolean = true,
)

sealed interface ProvidersListEvent {
    data class ShowError(val message: String) : ProvidersListEvent
}

/** One-tap presets for the provider form. 10.0.2.2 is the emulator's host loopback;
 * on a physical device replace it with your PC's LAN IP. */
enum class ProviderPreset(
    val label: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
) {
    OPENAI("OpenAI", "OpenAI", "https://api.openai.com", "gpt-4o-mini"),
    OLLAMA("Ollama", "Ollama (local)", "http://10.0.2.2:11434", ""),
    LM_STUDIO("LM Studio", "LM Studio (local)", "http://10.0.2.2:1234", ""),
}

data class ProviderEditState(
    val providerId: String? = null,
    val name: String = "",
    /** API root without the /v1 suffix — providers append their own versioned path. */
    val baseUrl: String = "https://api.openai.com",
    /** Optional model id sent with every chat request; blank = pick the provider's first model. */
    val model: String = "",
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
    private val localModelStore: LocalModelStore,
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _listState = MutableStateFlow(ProvidersListState())
    val listState: StateFlow<ProvidersListState> = _listState.asStateFlow()

    private val _editState = MutableStateFlow(ProviderEditState())
    val editState: StateFlow<ProviderEditState> = _editState.asStateFlow()

    /** On-device model status (download progress, ready, errors) for the Providers screen. */
    private val _localModelState = MutableStateFlow<LocalModelState>(LocalModelState.None)
    val localModelState: StateFlow<LocalModelState> = _localModelState.asStateFlow()

    /** The catalog of downloadable on-device models. */
    val localModels: List<LocalModelSpec> = localModelStore.availableModels

    init {
        viewModelScope.launch(dispatchers.main) {
            providerRepository.observeProviders().collect { providers ->
                _listState.update { it.copy(providers = providers, isLoading = false) }
            }
        }
        viewModelScope.launch(dispatchers.main) {
            localModelStore.status.collect { _localModelState.value = it }
        }
    }

    fun startLocalModelDownload() {
        localModels.firstOrNull()?.let { localModelStore.startDownload(it.id) }
    }

    fun deleteLocalModel() = localModelStore.deleteModel()

    fun cancelLocalModelDownload() = localModelStore.cancelDownload()

    /**
     * Sideloads a model picked from device storage (SAF). The Android layer opens the stream from
     * the [uri] and hands it to the pure-JVM store, which copies it in and marks it Ready.
     */
    fun importLocalModel(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val displayName = queryDisplayName(uri) ?: "Imported model"
            // Stable on-disk name so refresh()/runtime caching stay consistent across imports.
            localModelStore.importModel("imported-model.task", displayName) {
                runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

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
                    model = provider.model.orEmpty(),
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
                baseUrl = "https://api.openai.com",
                isNew = true,
            )
        }
    }

    /** Fill the form from a one-tap preset (cloud or local server). */
    fun applyPreset(preset: ProviderPreset) {
        _editState.update {
            it.copy(
                name = preset.providerName,
                baseUrl = preset.baseUrl,
                model = preset.model,
                verificationError = null,
                verificationSuccess = false,
            )
        }
    }

    fun onNameChange(name: String) {
        _editState.update { it.copy(name = name, verificationError = null, verificationSuccess = false) }
    }

    fun onBaseUrlChange(url: String) {
        _editState.update { it.copy(baseUrl = url, verificationError = null, verificationSuccess = false) }
    }

    fun onModelChange(model: String) {
        _editState.update { it.copy(model = model, verificationError = null, verificationSuccess = false) }
    }

    fun onApiKeyChange(key: String) {
        _editState.update { it.copy(apiKey = key, verificationError = null, verificationSuccess = false) }
    }

    fun onDefaultChange(isDefault: Boolean) {
        _editState.update { it.copy(isDefault = isDefault) }
    }

    /**
     * Verify the endpoint by issuing a lightweight listModels() call, then persist.
     * An empty API key is allowed — key-less local servers (Ollama, LM Studio) and
     * self-hosted gateways accept anonymous requests; cloud APIs surface a clear 401.
     * A stored base URL that already ends in /v1 is normalized to the API root so
     * requests never double-prefix (https://api.openai.com/v1/v1/... → 404).
     */
    fun verifyAndSave() {
        val state = _editState.value
        val name = state.name.trim()
        val baseUrl = state.baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        val model = state.model.trim()
        val apiKey = state.apiKey.trim()

        if (name.isEmpty()) {
            _editState.update { it.copy(verificationError = "Name is required") }
            return
        }

        viewModelScope.launch(dispatchers.main) {
            _editState.update { it.copy(isVerifying = true, verificationError = null, verificationSuccess = false) }

            val tempId = state.providerId ?: java.util.UUID.randomUUID().toString()
            val config = ProviderConfig(
                id = tempId,
                name = name,
                baseUrl = baseUrl,
                model = model.ifBlank { null },
                isDefault = state.isDefault,
            )

            // Temporarily store the key so the adapter can read it for the verify call
            if (apiKey.isEmpty()) {
                apiKeyStore.removeKey(tempId)
            } else {
                apiKeyStore.putKey(tempId, apiKey)
            }
            val adapter = providerManager.adapterFor(config)

            adapter.listModels()
                .onSuccess { models ->
                    // Persist for real
                    providerRepository.upsert(config)
                    if (state.isDefault) providerRepository.setDefault(config.id)
                    providerManager.dropAdapter(tempId) // drop temp; ProviderManager will recreate from DB
                    if (apiKey.isEmpty()) {
                        apiKeyStore.removeKey(config.id) // key-less local server: keep nothing stored
                    } else {
                        apiKeyStore.putKey(config.id, apiKey) // ensure key is under final ID
                    }

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
