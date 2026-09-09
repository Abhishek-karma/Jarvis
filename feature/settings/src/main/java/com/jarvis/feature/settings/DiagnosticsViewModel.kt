package com.jarvis.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.database.repository.DiagnosticsRepository
import com.jarvis.core.database.repository.RequestDiagnostics
import com.jarvis.core.network.ModelProfile
import com.jarvis.core.network.ProviderHealth
import com.jarvis.core.network.ProviderHealthTracker
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val traces: List<RequestDiagnostics> = emptyList(),
    val healthMap: Map<String, ProviderHealth> = emptyMap(),
    val activeProfile: ModelProfile = ModelProfile.BALANCED,
    val exportedReport: String? = null,
    val noticeMessage: String? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsRepository: DiagnosticsRepository,
    private val healthTracker: ProviderHealthTracker,
    private val userPreferences: UserPreferencesRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _notice = MutableStateFlow<String?>(null)
    private val _exportedReport = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        diagnosticsRepository.observeRecent(50),
        healthTracker.healthMap,
        userPreferences.modelProfile,
        _exportedReport,
        _notice,
    ) { traces, health, profileStr, report, notice ->
        DiagnosticsUiState(
            traces = traces,
            healthMap = health,
            activeProfile = ModelProfile.fromName(profileStr),
            exportedReport = report,
            noticeMessage = notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DiagnosticsUiState(),
    )

    fun setModelProfile(profile: ModelProfile) {
        viewModelScope.launch(dispatchers.io) {
            userPreferences.setModelProfile(profile.name.lowercase())
            _notice.value = "Model profile updated to ${profile.displayName}"
        }
    }

    fun exportReport() {
        viewModelScope.launch(dispatchers.io) {
            val report = diagnosticsRepository.generateSanitizedReport()
            _exportedReport.value = report
            _notice.value = "Diagnostic report generated"
        }
    }

    fun clearTraces() {
        viewModelScope.launch(dispatchers.io) {
            diagnosticsRepository.clearAll()
            _exportedReport.value = null
            _notice.value = "Diagnostics history cleared"
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
