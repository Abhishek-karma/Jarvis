package com.jarvis.core.database.repository

import com.jarvis.core.common.Conversation
import com.jarvis.core.common.Message
import kotlinx.coroutines.flow.Flow


interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun getConversation(id: String): Conversation?
    suspend fun getMessages(conversationId: String): List<Message>
    suspend fun upsertConversation(conversation: Conversation)
    suspend fun upsertMessage(message: Message)
    suspend fun upsertMessages(messages: List<Message>)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun renameConversation(id: String, title: String)
    /** Deletes default-titled, zero-message rows (legacy empty-chat cleanup). Returns how many. */
    suspend fun deleteEmptyConversations(defaultTitle: String): Int

    suspend fun deleteConversation(id: String)
    suspend fun deleteMessage(id: String)
    suspend fun deleteMessagesAfter(conversationId: String, fromCreatedAt: Long)
    suspend fun latestMessage(conversationId: String): Message?
}
