package com.jarvis.core.network.anthropic

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.JsonTreeAdapter
import com.jarvis.core.network.ToolDefinition
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Anthropic Messages API adapter contract tests per 13-TESTING.md §1 — 100% of the
 * [com.jarvis.core.network.LlmProvider] surface, against MockWebServer fixtures.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class AnthropicProviderTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var provider: AnthropicProvider
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockServer = MockWebServer()
        mockServer.start()

        val moshi = Moshi.Builder()
            .add(Any::class.java, JsonTreeAdapter)
            .build()
        val dispatchers = mockk<com.jarvis.core.common.DispatcherProvider>()
        every { dispatchers.main } returns testDispatcher
        every { dispatchers.io } returns testDispatcher
        every { dispatchers.default } returns testDispatcher

        provider = AnthropicProvider(
            id = "test-anthropic",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
            apiKeyProvider = { "test-api-key" },
            client = OkHttpClient(),
            moshi = moshi,
            dispatchers = dispatchers,
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun sseResponse(fixture: String): MockResponse = MockResponse()
        .setBody(fixture)
        .setHeader("Content-Type", "text/event-stream")

    /** Recorded request body parsed back into a plain object tree. */
    private fun recordedBody(): Map<*, *> {
        val body = mockServer.takeRequest().body.readUtf8()
        return JsonTreeAdapter.fromJson(JsonReader.of(okio.Buffer().writeUtf8(body))) as Map<*, *>
    }

    private fun textRequest() = ChatRequest(
        conversationHistory = listOf(
            Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
        ),
        model = "claude-3-5-sonnet",
    )

    // ── streamChat ────────────────────────────────────────────────────────────

    @Test
    fun `streamChat emits TokenDelta events from SSE stream`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/anthropic_stream_text.txt")!!.readText()
        mockServer.enqueue(sseResponse(fixture))

        val events = provider.streamChat(textRequest()).toList()

        val tokenDeltas = events.filterIsInstance<ChatStreamEvent.TokenDelta>()
            .filter { it.text.isNotEmpty() }
        assertEquals(2, tokenDeltas.size)
        assertEquals("Hello", tokenDeltas[0].text)
        assertEquals(" world", tokenDeltas[1].text)
        assertTrue(events.any { it is ChatStreamEvent.Done })
    }

    @Test
    fun `streamChat emits Usage with Anthropic token counts`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/anthropic_stream_text.txt")!!.readText()
        mockServer.enqueue(sseResponse(fixture))

        val events = provider.streamChat(textRequest()).toList()

        val usage = events.filterIsInstance<ChatStreamEvent.Usage>()
        assertEquals(1, usage.size)
        assertEquals(10, usage[0].promptTokens)
        assertEquals(12, usage[0].completionTokens)
    }

    @Test
    fun `streamChat sends Anthropic headers`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/anthropic_stream_text.txt")!!.readText()
        mockServer.enqueue(sseResponse(fixture))

        provider.streamChat(textRequest()).toList()

        val recorded = mockServer.takeRequest()
        assertEquals("test-api-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        assertTrue(recorded.path!!.startsWith("/v1/messages"))
    }

    // ── tool calling (v0.5 agent mode) ────────────────────────────────────────

    @Test
    fun `streamChat emits ToolCallRequested from streamed tool_use block`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/anthropic_stream_tool_use.txt")!!.readText()
        mockServer.enqueue(sseResponse(fixture))

        val events = provider.streamChat(textRequest()).toList()

        val calls = events.filterIsInstance<ChatStreamEvent.ToolCallRequested>()
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertEquals("""{"city":"London"}""", calls[0].argsJson)
        assertTrue(events.any { it is ChatStreamEvent.Done })
    }

    @Test
    fun `streamChat sends tools with input_schema objects in the body`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/anthropic_stream_text.txt")!!.readText()
        mockServer.enqueue(sseResponse(fixture))

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Weather in London?"),
            ),
            model = "claude-3-5-sonnet",
            toolsAvailable = listOf(
                ToolDefinition(
                    name = "get_weather",
                    description = "Get the current weather",
                    parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                ),
            ),
        )

        provider.streamChat(request).toList()

        val body = recordedBody()
        assertEquals("claude-3-5-sonnet", body["model"])
        assertEquals(4096.0, body["max_tokens"])
        // The JSON-Schema string is embedded as the real input_schema object, not a string.
        val tool = (body["tools"] as List<*>)[0] as Map<*, *>
        assertEquals("get_weather", tool["name"])
        assertEquals("Get the current weather", tool["description"])
        val schema = tool["input_schema"] as Map<*, *>
        assertEquals("object", schema["type"])
        val city = (schema["properties"] as Map<*, *>)["city"] as Map<*, *>
        assertEquals("string", city["type"])
    }

    @Test
    fun `streamChat round-trips tool_use and tool_result turns in the body`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/anthropic_stream_text.txt")!!.readText()
        mockServer.enqueue(sseResponse(fixture))

        val history = listOf(
            Message(conversationId = "c1", role = MessageRole.USER, content = "Weather in London?"),
            Message(
                conversationId = "c1",
                role = MessageRole.ASSISTANT,
                content = "",
                toolCallId = "toolu_1",
                toolCallName = "get_weather",
                toolCallArgsJson = """{"city":"London"}""",
            ),
            Message(
                conversationId = "c1",
                role = MessageRole.TOOL,
                content = "Sunny, 21C",
                toolCallId = "toolu_1",
                toolCallName = "get_weather",
            ),
        )

        provider.streamChat(ChatRequest(conversationHistory = history, model = "claude-3-5-sonnet")).toList()

        val body = recordedBody()
        val messages = body["messages"] as List<*>
        // Assistant turn carries the tool_use block with its input as an object.
        val assistant = messages.first { (it as Map<*, *>)["role"] == "assistant" } as Map<*, *>
        val toolUse = (assistant["content"] as List<*>)[0] as Map<*, *>
        assertEquals("tool_use", toolUse["type"])
        assertEquals("toolu_1", toolUse["id"])
        assertEquals("get_weather", toolUse["name"])
        assertEquals("London", (toolUse["input"] as Map<*, *>)["city"])
        // Observation travels as a user-role tool_result block paired by tool_use_id.
        val toolResultUser = messages.first { message ->
            val blocks = (message as Map<*, *>)["content"] as List<*>
            blocks.any { (it as Map<*, *>)["type"] == "tool_result" }
        } as Map<*, *>
        assertEquals("user", toolResultUser["role"])
        val toolResult = (toolResultUser["content"] as List<*>)[0] as Map<*, *>
        assertEquals("tool_result", toolResult["type"])
        assertEquals("toolu_1", toolResult["tool_use_id"])
        assertEquals("Sunny, 21C", toolResult["content"])
    }

    @Test
    fun `streamChat merges consecutive same-role messages into one wire message`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/anthropic_stream_text.txt")!!.readText()
        mockServer.enqueue(sseResponse(fixture))

        // A USER request followed by a USER rejection note (engine feedback path) must not
        // produce two adjacent user messages — Anthropic rejects that shape.
        val history = listOf(
            Message(conversationId = "c1", role = MessageRole.USER, content = "Do the thing"),
            Message(conversationId = "c1", role = MessageRole.USER, content = "Unknown tool \"nope\". Try again."),
        )

        provider.streamChat(ChatRequest(conversationHistory = history, model = "claude-3-5-sonnet")).toList()

        val body = recordedBody()
        val messages = body["messages"] as List<*>
        // Two consecutive USER turns collapse into one user message holding both text blocks.
        assertEquals(1, messages.size)
        val user = messages[0] as Map<*, *>
        assertEquals("user", user["role"])
        val contents = user["content"] as List<*>
        assertEquals(2, contents.size)
        assertEquals("text", (contents[0] as Map<*, *>)["type"])
        assertEquals("Do the thing", (contents[0] as Map<*, *>)["text"])
        assertTrue(((contents[1] as Map<*, *>)["text"] as String).contains("Unknown tool"))
    }

    // ── capabilities ──────────────────────────────────────────────────────────

    @Test
    fun `capabilities are correctly declared`() {
        assertTrue(provider.capabilities.supportsTools)
        assertTrue(provider.capabilities.vision)
        assertEquals(200_000, provider.capabilities.maxContext)
    }
}
