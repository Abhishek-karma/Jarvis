package com.jarvis.core.database

import com.jarvis.core.common.Conversation
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.database.dao.ConversationDao
import com.jarvis.core.database.dao.MessageDao
import com.jarvis.core.database.entity.ConversationEntity
import com.jarvis.core.database.entity.MessageEntity
import com.jarvis.core.database.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChatRepositoryTest {

    private val fakeConvDao = object : ConversationDao {
        val store = mutableListOf<ConversationEntity>()

        override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(store)

        override suspend fun get(id: String): ConversationEntity? = store.find { it.id == id }

        override suspend fun upsert(conversation: ConversationEntity) {
            val idx = store.indexOfFirst { it.id == conversation.id }
            if (idx >= 0) store[idx] = conversation else store.add(conversation)
        }

        override suspend fun setPinned(id: String, pinned: Boolean, nowMs: Long) {
            val idx = store.indexOfFirst { it.id == id }
            if (idx >= 0) store[idx] = store[idx].copy(pinned = pinned, updatedAt = nowMs)
        }

        override suspend fun rename(id: String, title: String, nowMs: Long) {
            val idx = store.indexOfFirst { it.id == id }
            if (idx >= 0) store[idx] = store[idx].copy(title = title, updatedAt = nowMs)
        }

        override suspend fun deleteEmptyByTitle(defaultTitle: String): Int = 0

        override suspend fun delete(id: String) {
            store.removeAll { it.id == id }
        }

        override suspend fun count(): Int = store.size
    }

    private val fakeMessageDao = object : MessageDao {
        val store = mutableListOf<MessageEntity>()

        override fun observeForConversation(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(store.filter { it.conversationId == conversationId }.sortedBy { it.createdAt })

        override suspend fun getForConversation(conversationId: String): List<MessageEntity> =
            store.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }

        override suspend fun upsert(message: MessageEntity) {
            val idx = store.indexOfFirst { it.id == message.id }
            if (idx >= 0) store[idx] = message else store.add(message)
        }

        override suspend fun upsertAll(messages: List<MessageEntity>) {
            messages.forEach { upsert(it) }
        }

        override suspend fun delete(id: String) {
            store.removeAll { it.id == id }
        }

        override suspend fun deleteAfter(conversationId: String, fromCreatedAt: Long) {
            store.removeAll { it.conversationId == conversationId && it.createdAt > fromCreatedAt }
        }

        override suspend fun latest(conversationId: String): MessageEntity? =
            store.filter { it.conversationId == conversationId }.maxByOrNull { it.createdAt }
    }

    private lateinit var repo: ChatRepository

    @BeforeEach
    fun setUp() {
        fakeConvDao.store.clear()
        fakeMessageDao.store.clear()
        repo = ChatRepository(fakeConvDao, fakeMessageDao)
    }

    @Test
    fun `upsert conversation and retrieve it`() = runTest {
        val conv = Conversation(id = "c1", title = "Topic")
        repo.upsertConversation(conv)
        val retrieved = repo.getConversation("c1")
        assertNotNull(retrieved)
        assertEquals("Topic", retrieved!!.title)
    }

    @Test
    fun `upsert messages preserves chronological order`() = runTest {
        val m1 = Message(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "first", createdAt = 100L)
        val m2 = Message(id = "m2", conversationId = "c1", role = MessageRole.ASSISTANT, content = "second", createdAt = 200L)
        val m3 = Message(id = "m3", conversationId = "c1", role = MessageRole.USER, content = "third", createdAt = 300L)

        repo.upsertMessages(listOf(m3, m1, m2))
        val list = repo.getMessages("c1")
        assertEquals(listOf("m1", "m2", "m3"), list.map { it.id })
    }

    @Test
    fun `deleteConversation removes conversation`() = runTest {
        repo.upsertConversation(Conversation(id = "c1", title = "Topic"))
        repo.deleteConversation("c1")
        assertNull(repo.getConversation("c1"))
    }

    @Test
    fun `deleteMessagesAfter removes only messages after timestamp`() = runTest {
        repo.upsertMessages(listOf(
            Message(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "1", createdAt = 100L),
            Message(id = "m2", conversationId = "c1", role = MessageRole.ASSISTANT, content = "2", createdAt = 200L),
            Message(id = "m3", conversationId = "c1", role = MessageRole.USER, content = "3", createdAt = 300L),
            Message(id = "m4", conversationId = "c1", role = MessageRole.ASSISTANT, content = "4", createdAt = 400L),
        ))
        repo.deleteMessagesAfter("c1", 200L)
        val remaining = repo.getMessages("c1")
        assertEquals(listOf("m1", "m2"), remaining.map { it.id })
    }

    @Test
    fun `latestMessage returns most recent message`() = runTest {
        repo.upsertMessages(listOf(
            Message(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "1", createdAt = 100L),
            Message(id = "m2", conversationId = "c1", role = MessageRole.ASSISTANT, content = "2", createdAt = 500L),
            Message(id = "m3", conversationId = "c1", role = MessageRole.USER, content = "3", createdAt = 300L),
        ))
        val latest = repo.latestMessage("c1")
        assertNotNull(latest)
        assertEquals("m2", latest!!.id)
    }

    @Test
    fun `renameConversation updates title`() = runTest {
        repo.upsertConversation(Conversation(id = "c1", title = "Original"))
        repo.renameConversation("c1", "Updated")
        assertEquals("Updated", repo.getConversation("c1")!!.title)
    }
}
