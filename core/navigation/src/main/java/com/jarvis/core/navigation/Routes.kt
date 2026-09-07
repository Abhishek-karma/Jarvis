package com.jarvis.core.navigation


object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val VOICE_MODE = "chat/voice"
    const val SETTINGS = "settings"
    const val ABOUT = "settings/about"
    const val PERMISSIONS = "settings/permissions"
    const val PROVIDERS_LIST = "providers"
    const val PROVIDER_EDIT = "provider/edit"
    const val MEMORY = "settings/memory"
    const val ROUTINES = "settings/routines"
    const val DIAGNOSTICS = "settings/diagnostics"

    /** Chat route with an optional conversationId argument. */
    const val CHAT_ARG_CONVERSATION_ID = "conversationId"
    const val PROVIDER_ARG_ID = "providerId"
    const val PROVIDER_ARG_TAB = "tab"

    fun providerEdit(providerId: String? = null): String =
        if (providerId == null) "$PROVIDER_EDIT?$PROVIDER_ARG_ID=" else "$PROVIDER_EDIT?$PROVIDER_ARG_ID=$providerId"

    fun providers(tab: String = "cloud"): String = "$PROVIDERS_LIST?$PROVIDER_ARG_TAB=$tab"
}
