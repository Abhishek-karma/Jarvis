package com.jarvis.core.database.repository

import com.jarvis.core.common.Conversation
import com.jarvis.core.common.Message
import com.jarvis.core.database.dao.ConversationDao
import com.jarvis.core.database.dao.MessageDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

typealias ChatRepository = ConversationRepository

@Singleton
open class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao? = null,
    private val messageDao: MessageDao? = null,
) {
    open fun observeConversations(): Flow<List<Conversation>> =
        conversationDao?.observeAll()?.map { list -> list.map { it.toDomain() } } ?: kotlinx.coroutines.flow.emptyFlow()

    open fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao?.observeForConversation(conversationId)?.map { list ->
            list.map { it.toDomain() }
        } ?: kotlinx.coroutines.flow.emptyFlow()

    open suspend fun getConversation(id: String): Conversation? =
        conversationDao?.get(id)?.toDomain()

    open suspend fun getMessages(conversationId: String): List<Message> =
        messageDao?.getForConversation(conversationId)?.map { it.toDomain() } ?: emptyList()

    open suspend fun upsertConversation(conversation: Conversation) {
        conversationDao?.upsert(conversation.toEntity())
    }

    open suspend fun upsertMessage(message: Message) {
        messageDao?.upsert(message.toEntity())
    }

    open suspend fun upsertMessages(messages: List<Message>) {
        messageDao?.upsertAll(messages.map { it.toEntity() })
    }

    open suspend fun setPinned(id: String, pinned: Boolean) {
        conversationDao?.setPinned(id, pinned, System.currentTimeMillis())
    }

    open suspend fun renameConversation(id: String, title: String) {
        conversationDao?.rename(id, title, System.currentTimeMillis())
    }

    /** Deletes default-titled, zero-message rows (legacy empty-chat cleanup). Returns how many. */
    open suspend fun deleteEmptyConversations(defaultTitle: String): Int =
        conversationDao?.deleteEmptyByTitle(defaultTitle) ?: 0

    open suspend fun deleteConversation(id: String) {
        conversationDao?.delete(id)
    }

    open suspend fun deleteMessage(id: String) {
        messageDao?.delete(id)
    }

    open suspend fun deleteMessagesAfter(conversationId: String, fromCreatedAt: Long) {
        messageDao?.deleteAfter(conversationId, fromCreatedAt)
    }

    open suspend fun latestMessage(conversationId: String): Message? =
        messageDao?.latest(conversationId)?.toDomain()
}
