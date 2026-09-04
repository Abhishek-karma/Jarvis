package com.jarvis.core.database.repository

import com.jarvis.core.common.Conversation
import com.jarvis.core.common.Message
import com.jarvis.core.database.dao.ConversationDao
import com.jarvis.core.database.dao.MessageDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for conversations and messages (02-ARCHITECTURE.md §3) — Hilt singleton
 * so multiple screens observing the same data stay in sync.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeForConversation(conversationId).map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun getConversation(id: String): Conversation? =
        conversationDao.get(id)?.toDomain()

    override suspend fun getMessages(conversationId: String): List<Message> =
        messageDao.getForConversation(conversationId).map { it.toDomain() }

    override suspend fun upsertConversation(conversation: Conversation) =
        conversationDao.upsert(conversation.toEntity())

    override suspend fun upsertMessage(message: Message) = messageDao.upsert(message.toEntity())

    override suspend fun upsertMessages(messages: List<Message>) =
        messageDao.upsertAll(messages.map { it.toEntity() })

    override suspend fun setPinned(id: String, pinned: Boolean) =
        conversationDao.setPinned(id, pinned, System.currentTimeMillis())

    override suspend fun renameConversation(id: String, title: String) =
        conversationDao.rename(id, title, System.currentTimeMillis())

    override suspend fun deleteConversation(id: String) = conversationDao.delete(id)

    override suspend fun deleteMessagesAfter(conversationId: String, fromCreatedAt: Long) =
        messageDao.deleteAfter(conversationId, fromCreatedAt)

    override suspend fun latestMessage(conversationId: String): Message? =
        messageDao.latest(conversationId)?.toDomain()
}
