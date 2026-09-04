package com.jarvis.core.ml

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ToolDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalPromptBuilderTest {
    private fun message(
        role: MessageRole,
        content: String,
    ) = Message(id = "m", conversationId = "c", role = role, content = content)

    @Test
    fun `builds a plain user assistant transcript`() {
        val prompt =
            LocalPromptBuilder.build(
                ChatRequest(
                    conversationHistory =
                        listOf(
                            message(MessageRole.USER, "Hi"),
                            message(MessageRole.ASSISTANT, "Hello!"),
                            message(MessageRole.USER, "What time is it?"),
                        ),
                    model = "gemma-2-2b-it",
                ),
            )
        assertEquals("User: Hi\n\nAssistant: Hello!\n\nUser: What time is it?\n\nAssistant:", prompt)
    }

    @Test
    fun `prepends the system prompt when present`() {
        val prompt =
            LocalPromptBuilder.build(
                ChatRequest(
                    conversationHistory = listOf(message(MessageRole.USER, "Hi")),
                    systemPrompt = "You are Jarvis.",
                    model = "gemma-2-2b-it",
                ),
            )
        assertTrue(prompt.startsWith("System: You are Jarvis."))
        assertTrue(prompt.endsWith("\n\nAssistant:"))
    }

    @Test
    fun `keeps tool results and drops blank assistant rows`() {
        val prompt =
            LocalPromptBuilder.build(
                ChatRequest(
                    conversationHistory =
                        listOf(
                            message(MessageRole.TOOL, "battery: 80"),
                            message(MessageRole.USER, "Check it"),
                            message(MessageRole.ASSISTANT, ""),
                        ),
                    model = "gemma-2-2b-it",
                ),
            )
        assertTrue(prompt.contains("Tool Result: battery: 80"))
        assertFalse(prompt.contains("Assistant: "))
        assertEquals("Tool Result: battery: 80\n\nUser: Check it\n\nAssistant:", prompt)
    }

    @Test
    fun `injects tool instructions when tools are available`() {
        val prompt =
            LocalPromptBuilder.build(
                ChatRequest(
                    conversationHistory = listOf(message(MessageRole.USER, "Check battery")),
                    model = "gemma-2-2b-it",
                    toolsAvailable =
                        listOf(
                            ToolDefinition("battery_level", "Get current battery percent", "{}"),
                        ),
                ),
            )
        assertTrue(prompt.contains("battery_level"))
        assertTrue(prompt.contains("<tool_call>"))
        assertTrue(prompt.contains("User: Check battery"))
    }

    @Test
    fun `renders an assistant tool-call turn back as a structured tool_call`() {
        val toolCallAssistant =
            Message(
                id = "m",
                conversationId = "c",
                role = MessageRole.ASSISTANT,
                content = "",
                toolCallName = "battery_level",
                toolCallArgsJson = "{}",
            )
        val prompt =
            LocalPromptBuilder.build(
                ChatRequest(
                    conversationHistory =
                        listOf(
                            toolCallAssistant,
                            message(MessageRole.TOOL, "battery: 80"),
                        ),
                    model = "gemma-2-2b-it",
                ),
            )
        assertTrue(prompt.contains("<tool_call>{\"name\":\"battery_level\",\"args\":{}}</tool_call>"))
        assertTrue(prompt.contains("Tool Result: battery: 80"))
    }

    @Test
    fun `caps history at the last MAX_TURNS pairs`() {
        val many =
            buildList {
                repeat(20) { i ->
                    add(message(MessageRole.USER, "u$i"))
                    add(message(MessageRole.ASSISTANT, "a$i"))
                }
            }
        val prompt =
            LocalPromptBuilder.build(
                ChatRequest(conversationHistory = many, model = "gemma-2-2b-it"),
            )
        // 8 pairs = 16 rows; assert the tail (u19/a19) is present and the head is gone.
        assertTrue(prompt.contains("User: u19"))
        assertTrue(prompt.contains("Assistant: a19"))
        assertFalse(prompt.contains("User: u0"))
        val rows = prompt.split("\n\n")
        assertEquals(17, rows.size) // 16 rows + trailing "Assistant:"
    }

    @Test
    fun `blank history yields an empty prompt`() {
        assertEquals("", LocalPromptBuilder.build(ChatRequest(conversationHistory = emptyList(), model = "m")))
    }
}
