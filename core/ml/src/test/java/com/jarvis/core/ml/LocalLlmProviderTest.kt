package com.jarvis.core.ml

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.ToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLlmProviderTest {
    private val spec =
        LocalModelSpec(
            id = "gemma-2-2b-it",
            displayName = "Gemma 2 2B",
            fileName = "gemma.task",
        )

    /** Captures the prompt and replays canned partials, or fails generation on demand. */
    private class FakeEngine(
        private val partials: List<String> = listOf("Hel", "lo", " Jarvis!"),
        private val fail: Boolean = false,
    ) : OnDeviceEngine {
        var lastPrompt: String? = null

        override suspend fun generate(
            prompt: String,
            onPartial: (String) -> Unit,
            onDone: () -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            lastPrompt = prompt
            if (fail) {
                onError(RuntimeException("native oom"))
                return
            }
            partials.forEach(onPartial)
            onDone()
        }

        override fun close() = Unit
    }

    private fun request(): ChatRequest =
        ChatRequest(
            conversationHistory =
                listOf(
                    Message(id = "1", conversationId = "c", role = MessageRole.USER, content = "Hello Jarvis"),
                ),
            model = spec.id,
        )

    @Test
    fun `streams token deltas and a Done event from engine partials`() =
        runTest {
            val engine = FakeEngine()
            val provider = LocalLlmProvider(id = "local-gemma", spec = spec, engine = engine)

            val events = provider.streamChat(request()).toList()

            val text = events.filterIsInstance<ChatStreamEvent.TokenDelta>().joinToString("") { it.text }
            assertEquals("Hello Jarvis!", text)
            assertTrue(events.last() is ChatStreamEvent.Done)
            // Prompt went through LocalPromptBuilder.
            assertTrue(engine.lastPrompt!!.contains("User: Hello Jarvis"))
            assertTrue(engine.lastPrompt!!.endsWith("\n\nAssistant:"))
        }

    @Test
    fun `maps engine failure to an Error event and never emits Done`() =
        runTest {
            val engine = FakeEngine(fail = true)
            val provider = LocalLlmProvider(id = "local-gemma", spec = spec, engine = engine)

            val events = provider.streamChat(request()).toList()

            val error = events.filterIsInstance<ChatStreamEvent.Error>().singleOrNull()
            assertTrue(error != null && error.code == "local")
            assertTrue(events.none { it is ChatStreamEvent.Done })
        }

    @Test
    fun `capabilities advertise a tools-capable agent model`() =
        runTest {
            val provider = LocalLlmProvider(id = "local-gemma", spec = spec, engine = FakeEngine())
            assertTrue(provider.capabilities.supportsTools)
            assertTrue(!provider.capabilities.vision)
            assertEquals(
                spec.id,
                provider
                    .listModels()
                    .getOrNull()!!
                    .single()
                    .id,
            )
        }

    @Test
    fun `parses a structured tool call into a ToolCallRequested in agent mode`() =
        runTest {
            val engine =
                FakeEngine(
                    partials =
                        listOf(
                            "I'll check. <tool_call>{\"name\":\"battery_level\",\"args\":{\"device\":\"phone\"}}</tool_call>",
                        ),
                )
            val provider = LocalLlmProvider(id = "local-gemma", spec = spec, engine = engine)
            val request =
                ChatRequest(
                    conversationHistory =
                        listOf(
                            Message(id = "1", conversationId = "c", role = MessageRole.USER, content = "Battery?"),
                        ),
                    model = spec.id,
                    toolsAvailable = listOf(ToolDefinition("battery_level", "Get battery", "{}")),
                )

            val events = provider.streamChat(request).toList()

            val call = events.filterIsInstance<ChatStreamEvent.ToolCallRequested>().singleOrNull()
            assertTrue(call != null && call.name == "battery_level")
            assertTrue(call!!.argsJson.contains("\"device\":\"phone\""))
            assertTrue(events.any { it is ChatStreamEvent.Done })
            // Prose outside the tool_call is still surfaced.
            assertTrue(
                events
                    .filterIsInstance<ChatStreamEvent.TokenDelta>()
                    .joinToString("") { it.text }
                    .contains("I'll check"),
            )
        }

    @Test
    fun `plain chat still streams a bare response without tool events`() =
        runTest {
            val provider = LocalLlmProvider(id = "local-gemma", spec = spec, engine = FakeEngine())
            val events = provider.streamChat(request()).toList()
            assertTrue(events.none { it is ChatStreamEvent.ToolCallRequested })
        }
}
