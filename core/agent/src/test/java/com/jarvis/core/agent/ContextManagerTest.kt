package com.jarvis.core.agent

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextManagerTest {

    private val contextManager = ContextManager()

    @Test
    fun `estimateTokens returns reasonable count based on character length`() {
        val text = "Hello world! This is a test query." // 34 chars -> ~9 tokens
        val tokens = contextManager.estimateTokens(text)
        assertTrue(tokens in 8..11)
    }

    @Test
    fun `compactHistory does not drop messages when within token budget`() {
        val messages = listOf(
            Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
            Message(conversationId = "c1", role = MessageRole.ASSISTANT, content = "Hello! How can I help?"),
            Message(conversationId = "c1", role = MessageRole.USER, content = "What's the weather?"),
        )

        val compacted = contextManager.compactHistory(messages, historyTokenBudget = 1000)
        assertEquals(3, compacted.messages.size)
        assertEquals(messages, compacted.messages)
        assertEquals(false, compacted.wasCompacted)
    }

    @Test
    fun `compactHistory keeps first turn and inserts summary note when budget exceeded`() {
        // Create 20 long messages
        val messages = (1..20).map { i ->
            Message(
                conversationId = "c1",
                role = if (i % 2 != 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "Turn $i: " + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(10),
            )
        }

        // Budget that fits only ~3 messages
        val compacted = contextManager.compactHistory(messages, historyTokenBudget = 400)

        assertEquals(true, compacted.wasCompacted)
        assertTrue(compacted.droppedMessagesCount > 0)
        // Verifies first message is preserved
        assertEquals(messages.first().content, compacted.messages.first().content)
        // Verifies a system compaction notice is inserted
        assertTrue(compacted.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("earlier messages compacted") })
        // Verifies the latest message is preserved
        assertEquals(messages.last().content, compacted.messages.last().content)
    }

    @Test
    fun `clampTextToBudget clamps content exceeding max token budget`() {
        val longText = "a".repeat(400)
        val clamped = contextManager.clampTextToBudget(longText, tokenBudget = 20)
        assertTrue(clamped.endsWith("… [truncated to fit context budget]"))
        assertTrue(clamped.length <= 150)
    }
}
