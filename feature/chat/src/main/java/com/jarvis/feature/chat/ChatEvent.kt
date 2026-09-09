package com.jarvis.feature.chat

sealed interface ChatUiEvent {
    data class ShowError(
        val message: String,
    ) : ChatUiEvent

    data class ShowNotice(
        val message: String,
    ) : ChatUiEvent
}
