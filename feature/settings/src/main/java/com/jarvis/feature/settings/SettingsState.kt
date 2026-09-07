package com.jarvis.feature.settings

import com.jarvis.core.common.LocalBenchmarkResult
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.preferences.ChatMode
import com.jarvis.core.preferences.ThemeMode

data class ProvidersListState(
    val providers: List<ProviderConfig> = emptyList(),
    val isLoading: Boolean = true,
)

/** User preferences rendered by the general settings screen (theme, agent, reasoning, local config). */
data class PreferencesState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val thinkMode: ThinkMode = ThinkMode.AUTO,
    val cautiousModeEnabled: Boolean = false,
    val appVersion: String = "",
    val agentStepCap: Int = 15,
    /** Settings → Mode: where Jarvis answers from by default (Local / Cloud). */
    val chatMode: ChatMode = ChatMode.CLOUD,
    /** Settings → Local: whether on-device runs may use internet-backed tools. */
    val localInternetAccess: Boolean = true,
    /** On-device inference temperature (0.0 to 1.5). */
    val localTemperature: Float = 0.7f,
    /** On-device top-p sampling parameter (0.1 to 1.0). */
    val localTopP: Float = 0.9f,
    /** On-device maximum context / output tokens ceiling. */
    val localMaxTokens: Int = 1024,
    /** On-device compute thread allocation (1..8). */
    val localThreads: Int = 4,
    /** Whether to prewarm the on-device engine into RAM. */
    val localPrewarm: Boolean = true,
    /** Latest on-device benchmark metrics. */
    val localBenchmarkResult: LocalBenchmarkResult? = null,
    /** Real-time benchmark execution status. */
    val isBenchmarking: Boolean = false,
    val benchmarkProgress: Float = 0f,
    val benchmarkStatusText: String = "",
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
