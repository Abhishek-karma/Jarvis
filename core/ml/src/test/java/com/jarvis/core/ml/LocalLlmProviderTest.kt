package com.jarvis.core.ml

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
            supportsTools = true,
        )

    private val chatOnlySpec =
        LocalModelSpec(
            id = "qwen3-0.6b",
            displayName = "Qwen 3 0.6B",
            fileName = "qwen3.litertlm",
            supportsTools = false,
            supportsReasoning = false,
        )

    /** Engine replaying mock events or failing on demand. */
    private class FakeEngine(
        private val eventsToEmit: List<ChatStreamEvent> = listOf(
            ChatStreamEvent.TokenDelta("Hel"),
            ChatStreamEvent.TokenDelta("lo"),
            ChatStreamEvent.TokenDelta(" Jarvis!"),
            ChatStreamEvent.Done,
        ),
        private val fail: Boolean = false,
    ) : OnDeviceEngine {
        var lastHistory: List<Message>? = null
        var lastSystemPrompt: String? = null
        var lastTools: List<ToolDefinition>? = null

        override fun streamChat(
            conversationHistory: List<Message>,
            systemPrompt: String?,
            tools: List<ToolDefinition>?,
            temperature: Double?,
        ): Flow<ChatStreamEvent> = flow {
            lastHistory = conversationHistory
            lastSystemPrompt = systemPrompt
            lastTools = tools

            if (fail) {
                emit(ChatStreamEvent.Error(code = "local", message = "native oom", retryable = false))
                return@flow
            }
            for (event in eventsToEmit) {
                emit(event)
            }
        }

        override suspend fun generate(
            prompt: String,
            temperature: Double?,
            onPartial: (String) -> Unit,
            onDone: () -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            if (fail) {
                onError(RuntimeException("native oom"))
                return
            }
            onPartial("Hello Jarvis!")
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

            assertEquals(1, engine.lastHistory?.size)
            assertEquals("Hello Jarvis", engine.lastHistory?.first()?.content)
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
    fun `capabilities reflect chat-only model when supportsTools is false`() =
        runTest {
            val provider = LocalLlmProvider(id = "local-qwen", spec = chatOnlySpec, engine = FakeEngine())
            assertTrue(!provider.capabilities.supportsTools)
            assertTrue(!provider.capabilities.supportsReasoning)
        }

    @Test
    fun `emits structured tool call ToolCallRequested in agent mode`() =
        runTest {
            val engine =
                FakeEngine(
                    eventsToEmit = listOf(
                        ChatStreamEvent.TokenDelta("I'll check. "),
                        ChatStreamEvent.ToolCallRequested("battery_level", "{\"device\":\"phone\"}"),
                        ChatStreamEvent.Done,
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

            assertTrue(
                events
                    .filterIsInstance<ChatStreamEvent.TokenDelta>()
                    .joinToString("") { it.text }
                    .contains("I'll check"),
            )
        }

    @Test
    fun `emits multiple structured tool calls into ToolCallRequested events in agent mode`() =
        runTest {
            val engine =
                FakeEngine(
                    eventsToEmit = listOf(
                        ChatStreamEvent.TokenDelta("Checking status. "),
                        ChatStreamEvent.ToolCallRequested("battery_level", "{}"),
                        ChatStreamEvent.ToolCallRequested("read_clipboard", "{}"),
                        ChatStreamEvent.Done,
                    ),
                )
            val provider = LocalLlmProvider(id = "local-gemma", spec = spec, engine = engine)
            val request =
                ChatRequest(
                    conversationHistory =
                        listOf(
                            Message(id = "1", conversationId = "c", role = MessageRole.USER, content = "Status?"),
                        ),
                    model = spec.id,
                    toolsAvailable = listOf(
                        ToolDefinition("battery_level", "Get battery", "{}"),
                        ToolDefinition("read_clipboard", "Read clipboard", "{}"),
                    ),
                )

            val events = provider.streamChat(request).toList()
            val calls = events.filterIsInstance<ChatStreamEvent.ToolCallRequested>()
            assertEquals(2, calls.size)
            assertEquals("battery_level", calls[0].name)
            assertEquals("read_clipboard", calls[1].name)
            assertTrue(events.any { it is ChatStreamEvent.Done })
        }

    @Test
    fun `plain chat still streams a bare response without tool events`() =
        runTest {
            val provider = LocalLlmProvider(id = "local-gemma", spec = spec, engine = FakeEngine())
            val events = provider.streamChat(request()).toList()
            assertTrue(events.none { it is ChatStreamEvent.ToolCallRequested })
        }
}

