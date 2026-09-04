package com.jarvis.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.Conversation
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.TimeGroup
import com.jarvis.core.common.TimeGrouping
import com.jarvis.core.database.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A time-grouped section of conversations for the History drawer. */
data class ConversationSection(
    val group: TimeGroup,
    val conversations: List<Conversation>,
)

data class HistoryUiState(
    val sections: List<ConversationSection> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * ViewModel for the History drawer. Observes all conversations, groups them by time, and
 * surfaces pin/rename/delete actions.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

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
            conversationRepository.setPinned(conversation.id, !conversation.pinned)
        }
    }

    fun rename(conversation: Conversation, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch(dispatchers.io) {
            conversationRepository.renameConversation(conversation.id, newTitle.trim())
        }
    }

    fun delete(conversation: Conversation) {
        viewModelScope.launch(dispatchers.io) {
            conversationRepository.deleteConversation(conversation.id)
        }
    }

    private fun groupConversations(conversations: List<Conversation>): List<ConversationSection> {
        val pinned = conversations.filter { it.pinned }.sortedByDescending { it.updatedAt }
        val unpinned = conversations.filter { !it.pinned }

        val grouped = unpinned.groupBy { TimeGrouping.groupFor(it.updatedAt) }
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
