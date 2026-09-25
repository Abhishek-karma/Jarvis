package com.jarvis.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.security.ApiKeyStore
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.network.update.UpdateCheckResult
import com.jarvis.core.network.update.UpdateChecker
import com.jarvis.core.preferences.ChatMode
import com.jarvis.core.preferences.ThemeMode
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared ViewModel for the settings flow. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val providerRepository: ProviderRepository,
        private val providerManager: ProviderManager,
        private val apiKeyStore: ApiKeyStore,
        private val userPreferences: UserPreferencesRepository,
        private val updateChecker: UpdateChecker,
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

        init {
            val version =
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
            _prefsState.update { it.copy(appVersion = version) }
        }

        /** Manual update check state for the Settings "App updates" row. */
        private val _updateCheck = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
        val updateCheck: StateFlow<UpdateCheckState> = _updateCheck.asStateFlow()

        /** Checks GitHub Releases for a newer version; result surfaces in [updateCheck]. */
        fun checkForUpdates() {
            if (_updateCheck.value is UpdateCheckState.Checking) return
            viewModelScope.launch(dispatchers.io) {
                _updateCheck.value = UpdateCheckState.Checking
                _updateCheck.value =
                    when (val result = updateChecker.check(_prefsState.value.appVersion)) {
                        is UpdateCheckResult.UpdateAvailable ->
                            UpdateCheckState.Available(result.latestVersion, result.apkUrl)
                        UpdateCheckResult.UpToDate -> UpdateCheckState.UpToDate
                        UpdateCheckResult.Unavailable -> UpdateCheckState.Failed
                    }
            }
        }

        /** Opens the APK download in the browser; called from the UI with an activity context. */
        fun openUpdateDownload(url: String) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        init {
            viewModelScope.launch(dispatchers.main) {
                providerRepository.observeProviders().collect { providers ->
                    _listState.update { it.copy(providers = providers, isLoading = false) }
                    apiKeyStore.removeKeysNotIn(providers.map { it.id }.toSet())
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
                userPreferences.planFirstMode.collect { enabled ->
                    _prefsState.update { it.copy(planFirstMode = enabled) }
                }
            }
            viewModelScope.launch(dispatchers.main) {
                userPreferences.agentStepCap.collect { cap -> _prefsState.update { it.copy(agentStepCap = cap) } }
            }
            viewModelScope.launch(dispatchers.main) {
                userPreferences.chatMode.collect { mode -> _prefsState.update { it.copy(chatMode = mode) } }
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

        fun setPlanFirstMode(enabled: Boolean) {
            viewModelScope.launch(dispatchers.main) { userPreferences.setPlanFirstMode(enabled) }
        }

        fun setAgentStepCap(cap: Int) {
            viewModelScope.launch(dispatchers.main) { userPreferences.setAgentStepCap(cap) }
        }

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

                val previousKey = if (state.isNew) null else apiKeyStore.getKey(tempId)
                if (apiKey.isEmpty()) {
                    apiKeyStore.removeKey(tempId)
                } else {
                    apiKeyStore.putKey(tempId, apiKey)
                }

                providerManager.dropAdapter(tempId)
                val adapter = providerManager.adapterFor(config)

                val result =
                    try {
                        adapter.listModels()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }

                result
                    .onSuccess {
                        providerRepository.upsert(config)
                        if (state.isDefault) providerRepository.setDefault(config.id)
                        providerManager.dropAdapter(tempId)
                        if (apiKey.isEmpty()) {
                            apiKeyStore.removeKey(config.id)
                        } else {
                            apiKeyStore.putKey(config.id, apiKey)
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
                        if (previousKey != null) apiKeyStore.putKey(tempId, previousKey) else apiKeyStore.removeKey(tempId)
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
            deleteProvider(id)
        }
    }
