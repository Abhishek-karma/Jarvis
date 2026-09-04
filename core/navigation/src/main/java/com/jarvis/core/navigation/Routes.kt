package com.jarvis.core.navigation

/**
 * Cross-feature navigation contract (02-ARCHITECTURE.md §2): route definitions only,
 * no ViewModel sharing. Features depend on this module, never on each other.
 */
object Routes {
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val VOICE_MODE = "chat/voice"
    const val SETTINGS = "settings"
    const val ABOUT = "settings/about"
    const val PROVIDERS_LIST = "providers"
    const val PROVIDER_EDIT = "provider/edit"

    /** Chat route with an optional conversationId argument. */
    const val CHAT_ARG_CONVERSATION_ID = "conversationId"
    const val PROVIDER_ARG_ID = "providerId"

    fun chat(conversationId: String? = null): String =
        if (conversationId == null) CHAT else "$CHAT?$CHAT_ARG_CONVERSATION_ID=$conversationId"

    fun providerEdit(providerId: String? = null): String =
        if (providerId == null) "$PROVIDER_EDIT?$PROVIDER_ARG_ID=" else "$PROVIDER_EDIT?$PROVIDER_ARG_ID=$providerId"
}
