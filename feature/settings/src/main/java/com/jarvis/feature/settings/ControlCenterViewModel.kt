package com.jarvis.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.agent.bridge.BridgeCoordinator
import com.jarvis.core.agent.bridge.BridgeStatus
import com.jarvis.core.agent.bridge.BridgeSystemState
import com.jarvis.core.agent.bridge.BridgeTier
import com.jarvis.core.agent.bridge.BridgeTierInfo
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ControlCenterUiState(
    val activeTier: BridgeTier = BridgeTier.SANDBOX,
    val tiers: List<BridgeTierInfo> = emptyList(),
    val isExpertModeEnabled: Boolean = false,
    val isShizukuEnabled: Boolean = true,
    val isProbing: Boolean = false,
    val blockedRulesSummary: List<String> = listOf(
        "Destructive recursive deletion (rm -rf /)",
        "Device wipe / factory reset (wipe data)",
        "Filesystem format (mkfs)",
        "Block device direct writes (dd of=/dev/...)",
        "Firmware flashing (flash_image)",
        "Un-provision device (device_provisioned 0)",
        "System package silent uninstalls (com.android.*)",
    ),
)

sealed interface ControlCenterEvent {
    data class ShowToast(val message: String) : ControlCenterEvent
    data object OpenShizukuApp : ControlCenterEvent
}

@HiltViewModel
class ControlCenterViewModel @Inject constructor(
    private val bridgeCoordinator: BridgeCoordinator,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ControlCenterUiState())
    val uiState: StateFlow<ControlCenterUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ControlCenterEvent>()
    val events: SharedFlow<ControlCenterEvent> = _events.asSharedFlow()

    init {
        loadState()
    }

    fun loadState() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProbing = true)
            val expertMode = preferencesRepository.expertShellEnabled.first()
            val shizukuEnabled = preferencesRepository.shizukuEnabled.first()
            val systemState = bridgeCoordinator.probeBridges(isExpertMode = expertMode)

            _uiState.value = _uiState.value.copy(
                activeTier = systemState.activeTier,
                tiers = systemState.tiers,
                isExpertModeEnabled = expertMode,
                isShizukuEnabled = shizukuEnabled,
                isProbing = false,
            )
        }
    }

    fun toggleExpertMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setExpertShellEnabled(enabled)
            _uiState.value = _uiState.value.copy(isExpertModeEnabled = enabled)
            _events.emit(
                ControlCenterEvent.ShowToast(
                    if (enabled) "Expert shell mode enabled. Destructive commands remain blocked."
                    else "Expert shell mode disabled."
                )
            )
        }
    }

    fun toggleShizuku(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShizukuEnabled(enabled)
            _uiState.value = _uiState.value.copy(isShizukuEnabled = enabled)
            loadState()
        }
    }

    fun onLaunchShizuku() {
        viewModelScope.launch {
            _events.emit(ControlCenterEvent.OpenShizukuApp)
        }
    }

    fun onRequestShizukuPermission() {
        bridgeCoordinator.requestShizukuPermission()
        loadState()
    }
}
