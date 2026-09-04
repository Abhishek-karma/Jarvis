package com.jarvis.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.Conversation
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.TimeGroup
import com.jarvis.core.common.TimeGrouping
import com.jarvis.core.database.repository.ConversationRepository
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

/** A time-grouped section of conversations for the History drawer. */
data class ConversationSection(
    val group: TimeGroup,
    val conversations: List<Conversation>,
)

sealed interface HistoryUiEvent {
    data class ShowError(
        val message: String,
    ) : HistoryUiEvent

    data class ShowMessage(
        val message: String,
    ) : HistoryUiEvent
}

data class HistoryUiState(
    val sections: List<ConversationSection> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * ViewModel for the History drawer. Observes all conversations, groups them by time, and
 * surfaces pin/rename/delete actions with one-shot toasts.
 */
@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HistoryUiState())
        val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

        /** One-shot toasts; buffered so fire-and-forget emissions never drop. */
        private val _uiEvents = MutableSharedFlow<HistoryUiEvent>(extraBufferCapacity = 8)
        val uiEvents: SharedFlow<HistoryUiEvent> = _uiEvents.asSharedFlow()

        init {
            viewModelScope.launch(dispatchers.main) {
                conversationRepository.observeConversations().collect { conversations ->
                    val grouped = groupConversations(conversations)
                    _uiState.update { it.copy(sections = grouped, isLoading = false) }
                }
            }
        }

        fun togglePin(conversation: Conversation) {
            viewModelScope.launch(dispatchers.io) {
                runCatching { conversationRepository.setPinned(conversation.id, !conversation.pinned) }
                    .onFailure { _uiEvents.tryEmit(HistoryUiEvent.ShowError("Could not pin: ${it.message}")) }
            }
        }

        fun rename(
            conversation: Conversation,
            newTitle: String,
        ) {
            if (newTitle.isBlank()) return
            viewModelScope.launch(dispatchers.io) {
                runCatching { conversationRepository.renameConversation(conversation.id, newTitle.trim()) }
                    .onSuccess { _uiEvents.tryEmit(HistoryUiEvent.ShowMessage("Renamed")) }
                    .onFailure { _uiEvents.tryEmit(HistoryUiEvent.ShowError("Could not rename: ${it.message}")) }
            }
        }

        /**
         * Deletes a conversation. The ChatViewModel reacts to the removal itself (the messages
         * observer fires with an empty list / the chat switches to a new conversation), so the
         * drawer stays a pure list.
         */
        fun delete(conversation: Conversation) {
            viewModelScope.launch(dispatchers.io) {
                runCatching { conversationRepository.deleteConversation(conversation.id) }
                    .onSuccess { _uiEvents.tryEmit(HistoryUiEvent.ShowMessage("Conversation deleted")) }
                    .onFailure { _uiEvents.tryEmit(HistoryUiEvent.ShowError("Could not delete: ${it.message}")) }
            }
        }

        private fun groupConversations(conversations: List<Conversation>): List<ConversationSection> {
            val pinned = conversations.filter { it.pinned }.sortedByDescending { it.updatedAt }
            val unpinned = conversations.filter { !it.pinned }

            val grouped =
                unpinned
                    .groupBy { TimeGrouping.groupFor(it.updatedAt) }
                    .map { (group, list) ->
                        ConversationSection(
                            group = group,
                            conversations = list.sortedByDescending { it.updatedAt },
                        )
                    }

            val sections = mutableListOf<ConversationSection>()
            if (pinned.isNotEmpty()) {
                sections.add(ConversationSection(TimeGroup.PINNED, pinned))
            }
            TimeGrouping.ORDER.filter { it != TimeGroup.PINNED }.forEach { group ->
                grouped.firstOrNull { it.group == group }?.let { sections.add(it) }
            }
            return sections
        }
    }
