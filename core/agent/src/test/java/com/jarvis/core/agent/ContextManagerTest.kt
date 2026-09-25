package com.jarvis.core.agent

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `compactHistory preserves tool call and tool result as atomic pair`() {
        // Build a conversation with user turns and a multi-step tool turn in the middle
        val initialUser = Message(conversationId = "c1", role = MessageRole.USER, content = "Start task")
        val assistant1 = Message(conversationId = "c1", role = MessageRole.ASSISTANT, content = "Starting")

        val toolCallMsg = Message(
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallId = "call_123",
            toolCallName = "get_battery_status",
            toolCallArgsJson = "{}",
        )
        val toolResultMsg = Message(
            conversationId = "c1",
            role = MessageRole.TOOL,
            content = "Battery is 85%",
            toolCallId = "call_123",
            toolCallName = "get_battery_status",
        )
        val followUpAssistant = Message(
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "Battery level retrieved.",
        )

        val latestUser = Message(conversationId = "c1", role = MessageRole.USER, content = "Next question")

        val messages = listOf(
            initialUser,
            assistant1,
            toolCallMsg,
            toolResultMsg,
            followUpAssistant,
            latestUser,
        )

        // When budget fits all, all are returned
        val fullCompacted = contextManager.compactHistory(messages, historyTokenBudget = 2000)
        assertEquals(6, fullCompacted.messages.size)

        // When budget forces dropping middle messages, tool call and tool result are NEVER orphaned!
        val tightCompacted = contextManager.compactHistory(messages, historyTokenBudget = 60)
        val resultMessages = tightCompacted.messages

        // Check that if toolCallMsg is present, toolResultMsg MUST be present
        val hasToolCall = resultMessages.any { it.toolCallId == "call_123" && it.role == MessageRole.ASSISTANT }
        val hasToolResult = resultMessages.any { it.toolCallId == "call_123" && it.role == MessageRole.TOOL }

        assertEquals(hasToolCall, hasToolResult, "Tool call and Tool result must either both be present or both dropped!")
    }

    @Test
    fun `clampObservation retains error warnings and clamps long output`() {
        val longErrorOutput = "HTTP 500 Internal Server Error: Database timeout occurred while querying logs.\n" +
            "stacktrace:\n" + "at com.jarvis.Server.query(Server.kt:42)\n".repeat(100) +
            "Final line: execution failed."

        val clamped = contextManager.clampObservation(longErrorOutput, maxTokens = 50)

        assertTrue(clamped.contains("error/exception warnings") || clamped.contains("clamped"))
        assertTrue(clamped.contains("HTTP 500 Internal Server Error"))
        assertTrue(clamped.contains("Final line: execution failed"))
        assertTrue(contextManager.estimateTokens(clamped) < 100)
    }

    @Test
    fun `prepareContext guarantees active user request is never dropped under overflow`() {
        val budget = ContextBudget.forLocal(maxTotalTokens = 1024)
        val messages = (1..30).map { i ->
            Message(
                conversationId = "c1",
                role = if (i % 2 != 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "Turn $i content with substantial length ".repeat(20),
            )
        }

        val memory = "- Fact: Alex lives in SF\n".repeat(20)

        val prepared = contextManager.prepareContext(
            history = messages,
            memoryContext = memory,
            budget = budget,
            systemPromptTokens = 200,
            toolsTokens = 200,
        )

        assertTrue(prepared.messages.isNotEmpty())
        assertEquals(messages.last().content, prepared.messages.last().content, "The active user request must never be dropped!")
        assertTrue(prepared.wasCompacted)
    }
}
