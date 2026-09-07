package com.jarvis.feature.settings

import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.preferences.ChatMode
import com.jarvis.core.preferences.ThemeMode

data class ProvidersListState(
    val providers: List<ProviderConfig> = emptyList(),
    val isLoading: Boolean = true,
)

/** User preferences rendered by the general settings screen (theme, agent, reasoning). */
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
