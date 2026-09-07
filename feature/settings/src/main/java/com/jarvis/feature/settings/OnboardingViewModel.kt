package com.jarvis.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The onboarding steps, in order. */
enum class OnboardingStep {
    /** What Jarvis is, one CTA. */
    WELCOME,

    /** Pick a cloud provider or use the on-device model. */
    SETUP,

    /** Microphone permission for voice input. */
    PERMISSIONS,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    /** True when a cloud provider already exists — tunes the SETUP step's copy. */
    val hasProvider: Boolean = false,
)

sealed interface OnboardingUiEvent {
    data class ShowError(
        val message: String,
    ) : OnboardingUiEvent
}


@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val userPreferences: UserPreferencesRepository,
        private val providerRepository: ProviderRepository,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        private val _uiEvents = MutableSharedFlow<OnboardingUiEvent>(extraBufferCapacity = 8)
        val uiEvents: SharedFlow<OnboardingUiEvent> = _uiEvents.asSharedFlow()

        /** Emitted exactly once when the flow should hand off to Chat. */
        private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val finished: SharedFlow<Unit> = _finished.asSharedFlow()

        init {

            viewModelScope.launch(dispatchers.main) {
                providerRepository.observeProviders().collect { providers ->
                    _uiState.update { it.copy(hasProvider = providers.isNotEmpty()) }
                }
            }
        }

        fun advance() {
            val next =
                when (_uiState.value.step) {
                    OnboardingStep.WELCOME -> OnboardingStep.SETUP
                    OnboardingStep.SETUP -> OnboardingStep.PERMISSIONS
                    OnboardingStep.PERMISSIONS -> return
                }
            _uiState.update { it.copy(step = next) }
        }

        fun back() {
            val previous =
                when (_uiState.value.step) {
                    OnboardingStep.WELCOME -> null
                    OnboardingStep.SETUP -> OnboardingStep.WELCOME
                    OnboardingStep.PERMISSIONS -> OnboardingStep.SETUP
                }
            if (previous != null) {
                _uiState.update { it.copy(step = previous) }
            }
        }


        fun complete() {
            viewModelScope.launch(dispatchers.main) {


                runCatching { userPreferences.setOnboardingCompleted(true) }
                    .onFailure {
                        _uiEvents.tryEmit(OnboardingUiEvent.ShowError(it.message ?: "Could not save setup"))
                        return@launch
                    }
                _finished.tryEmit(Unit)
            }
        }
    }
