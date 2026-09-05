package com.jarvis.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.ml.LocalModelSpec
import com.jarvis.core.ml.LocalModelState
import com.jarvis.core.ml.LocalModelStore
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.preferences.ThemeMode
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProvidersListState(
    val providers: List<ProviderConfig> = emptyList(),
    val isLoading: Boolean = true,
)

/** User preferences rendered by the general settings screen (theme, agent, reasoning). */
data class PreferencesState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val thinkMode: ThinkMode = ThinkMode.AUTO,
    val cautiousModeEnabled: Boolean = false,
    val agentStepCap: Int = 15,
)

sealed interface ProvidersListEvent {
    data class ShowError(
        val message: String,
    ) : ProvidersListEvent

    data class ShowMessage(
        val message: String,
    ) : ProvidersListEvent
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
    /** Wire family — drives which adapter ProviderManager dispatches to. */
    val type: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    val isVerifying: Boolean = false,
    val verificationError: String? = null,
    /** True once the provider has been verified and persisted — drives the back navigation. */
    val verificationSuccess: Boolean = false,
    val isNew: Boolean = true,
)

/** Shared ViewModel for the settings flow. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val providerRepository: ProviderRepository,
        private val providerManager: ProviderManager,
        private val apiKeyStore: ApiKeyStore,
        private val localModelStore: LocalModelStore,
        private val userPreferences: UserPreferencesRepository,
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val _listState = MutableStateFlow(ProvidersListState())
        val listState: StateFlow<ProvidersListState> = _listState.asStateFlow()

    private val _prefsState = MutableStateFlow(PreferencesState())
    val prefsState: StateFlow<PreferencesState> = _prefsState.asStateFlow()

        /** One-shot toasts for list actions. */
        private val _listEvents = MutableSharedFlow<ProvidersListEvent>(extraBufferCapacity = 8)
        val listEvents: SharedFlow<ProvidersListEvent> = _listEvents.asSharedFlow()

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
                userPreferences.themeMode.collect { mode -> _prefsState.update { it.copy(themeMode = mode) } }
            }
            viewModelScope.launch(dispatchers.main) {
                userPreferences.thinkMode.collect { mode -> _prefsState.update { it.copy(thinkMode = mode) } }
            }
            viewModelScope.launch(dispatchers.main) {
                userPreferences.cautiousModeEnabled.collect { enabled ->
                    _prefsState.update { it.copy(cautiousModeEnabled = enabled) }
                }
            }
            viewModelScope.launch(dispatchers.main) {
                userPreferences.agentStepCap.collect { cap -> _prefsState.update { it.copy(agentStepCap = cap) } }
            }
            viewModelScope.launch(dispatchers.main) {
                var previous: LocalModelState = LocalModelState.None
                localModelStore.status.collect { state ->
                    _localModelState.value = state
                    if (state is LocalModelState.Error &&
                        (previous is LocalModelState.Downloading || previous is LocalModelState.Importing)
                    ) {
                        _listEvents.tryEmit(ProvidersListEvent.ShowError(state.message))
                    }
                    previous = state
                }
            }
        }

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch(dispatchers.main) { userPreferences.setThemeMode(mode) }
        }

        fun setThinkMode(mode: ThinkMode) {
            viewModelScope.launch(dispatchers.main) { userPreferences.setThinkMode(mode) }
        }

        fun setCautiousMode(enabled: Boolean) {
            viewModelScope.launch(dispatchers.main) { userPreferences.setCautiousModeEnabled(enabled) }
        }

        fun setAgentStepCap(cap: Int) {
            viewModelScope.launch(dispatchers.main) { userPreferences.setAgentStepCap(cap) }
        }

        fun startLocalModelDownload() {
            if (_localModelState.value is LocalModelState.Ready) return
            val spec = localModels.firstOrNull() ?: return
            localModelStore.startDownload(spec.id)
            _listEvents.tryEmit(
                ProvidersListEvent.ShowMessage("Downloading ${spec.displayName}. Keep the app open."),
            )
        }

        fun deleteLocalModel() {
            viewModelScope.launch(dispatchers.io) {
                localModelStore.deleteModel()
                _listEvents.tryEmit(ProvidersListEvent.ShowMessage("On-device model removed"))
            }
        }

        fun cancelLocalModelDownload() {
            if (_localModelState.value is LocalModelState.Downloading) {
                localModelStore.cancelDownload()
                _listEvents.tryEmit(ProvidersListEvent.ShowMessage("Download cancelled"))
            }
        }

        fun importLocalModel(uri: Uri) {
            when (_localModelState.value) {
                is LocalModelState.Downloading,
                is LocalModelState.Importing,
                is LocalModelState.Ready,
                -> return
                else -> Unit
            }
            _listEvents.tryEmit(ProvidersListEvent.ShowMessage("Importing model. This can take a few minutes."))
            viewModelScope.launch(dispatchers.io) {
                val displayName = queryDisplayName(uri) ?: "Imported model"
                // Stable on-disk name so refresh()/runtime caching stay consistent across imports.
                localModelStore
                    .importModel("imported-model.task", displayName) {
                        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    }.onSuccess {
                        _listEvents.tryEmit(
                            ProvidersListEvent.ShowMessage("Model imported. Local routing is now available."),
                        )
                    }.onFailure { error ->
                        // Failures flip the store to Error (card + collector snackbar). Only toast here
                        // when the store refused without a state change (guard race), so nothing double-shows.
                        if (_localModelState.value !is LocalModelState.Error) {
                            _listEvents.tryEmit(
                                ProvidersListEvent.ShowError(error.message ?: "Import failed"),
                            )
                        }
                    }
            }
        }

        private fun queryDisplayName(uri: Uri): String? =
            runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()

        fun deleteProvider(id: String) {
            viewModelScope.launch(dispatchers.io) {
                val name = providerRepository.getProvider(id)?.name
                providerManager.dropAdapter(id)
                providerRepository.delete(id)
                apiKeyStore.removeKey(id)
                _listEvents.tryEmit(
                    ProvidersListEvent.ShowMessage(
                        if (name.isNullOrBlank()) "Provider deleted" else "“$name” deleted",
                    ),
                )
            }
        }

        fun setDefault(id: String) {
            viewModelScope.launch(dispatchers.io) {
                providerRepository.setDefault(id)
                val name = providerRepository.getProvider(id)?.name
                _listEvents.tryEmit(
                    ProvidersListEvent.ShowMessage(
                        if (name.isNullOrBlank()) "Default updated" else "“$name” set as default",
                    ),
                )
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
                        type = provider.type,
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
                    type = ProviderType.OPENAI_COMPATIBLE,
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
         * Switching the provider family re-points the base URL to that family's canonical
         * root unless the user has already typed a custom one (we can't tell intent from a
         * blank field, so we only auto-fill on a fresh family switch).
         */
        fun onTypeChange(type: ProviderType) {
            _editState.update { state ->
                val canonical = canonicalBaseUrl(type)
                val url =
                    if (state.baseUrl == canonicalBaseUrl(state.type) ||
                        state.baseUrl == "https://api.openai.com"
                    ) {
                        canonical
                    } else {
                        state.baseUrl
                    }
                state.copy(type = type, baseUrl = url, verificationError = null, verificationSuccess = false)
            }
        }

        private fun canonicalBaseUrl(type: ProviderType): String =
            when (type) {
                ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com"
                ProviderType.ANTHROPIC -> "https://api.anthropic.com"
                ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
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
            val baseUrl =
                state.baseUrl
                    .trim()
                    .trimEnd('/')
                    .removeSuffix("/v1")
            val model = state.model.trim()
            val apiKey = state.apiKey.trim()

            if (name.isEmpty()) {
                _editState.update { it.copy(verificationError = "Name is required") }
                return
            }

            viewModelScope.launch(dispatchers.main) {
                _editState.update { it.copy(isVerifying = true, verificationError = null, verificationSuccess = false) }

                val tempId =
                    state.providerId ?: java.util.UUID
                        .randomUUID()
                        .toString()
                val config =
                    ProviderConfig(
                        id = tempId,
                        name = name,
                        baseUrl = baseUrl,
                        model = model.ifBlank { null },
                        type = state.type,
                        isDefault = state.isDefault,
                    )

                // Temporarily store the key so the adapter can read it for the verify call
                if (apiKey.isEmpty()) {
                    apiKeyStore.removeKey(tempId)
                } else {
                    apiKeyStore.putKey(tempId, apiKey)
                }
                val adapter = providerManager.adapterFor(config)

                adapter
                    .listModels()
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
                        _listEvents.tryEmit(
                            ProvidersListEvent.ShowMessage(
                                if (state.isNew) "“$name” added" else "“$name” updated",
                            ),
                        )
                    }.onFailure { error ->
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
                _listEvents.tryEmit(ProvidersListEvent.ShowMessage("Provider deleted"))
            }
        }
    }
