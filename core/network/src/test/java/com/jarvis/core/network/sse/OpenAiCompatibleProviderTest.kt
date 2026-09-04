package com.jarvis.core.network.sse

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.JsonTreeAdapter
import com.jarvis.core.network.ToolDefinition
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
 * Provider contract tests: 100% of the [LlmProvider] interface surface, against
 * MockWebServer with recorded fixtures — no real provider endpoint.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OpenAiCompatibleProviderTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var provider: OpenAiCompatibleProvider
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockServer = MockWebServer()
        mockServer.start()

        val moshi = Moshi.Builder()
            .add(Any::class.java, JsonTreeAdapter)
            .build()
        val client = OkHttpClient()
        val dispatchers = mockk<com.jarvis.core.common.DispatcherProvider>()
        every { dispatchers.main } returns testDispatcher
        every { dispatchers.io } returns testDispatcher
        every { dispatchers.default } returns testDispatcher

        provider = OpenAiCompatibleProvider(
            id = "test-provider",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
            apiKeyProvider = { "test-api-key" },
            client = client,
            moshi = moshi,
            dispatchers = dispatchers,
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `listModels returns models on 200 response`() = runTest {
        val fixture = javaClass.classLoader!!.getResource("fixtures/models_list.json")!!.readText()
        mockServer.enqueue(MockResponse().setBody(fixture).setResponseCode(200))

        val result = provider.listModels()

        assertTrue(result.isSuccess)
        val models = result.getOrNull()!!
        assertEquals(2, models.size)
        assertEquals("gpt-4o", models[0].id)
        assertEquals("gpt-4o-mini", models[1].id)
    }

    @Test
    fun `stored base URL ending in v1 never double-prefixes the version path`() = runTest {
        // The settings form used to default to "https://api.openai.com/v1", and providers
        // append "/v1/..." themselves — that produced /v1/v1/models → 404. apiRoot() must
        // normalize whether or not the stored URL carries the suffix.
        val fixture = javaClass.classLoader!!.getResource("fixtures/models_list.json")!!.readText()
        mockServer.enqueue(MockResponse().setBody(fixture).setResponseCode(200))

        val client = OkHttpClient()
        val dispatchers = mockk<com.jarvis.core.common.DispatcherProvider>()
        every { dispatchers.main } returns testDispatcher
        every { dispatchers.io } returns testDispatcher
        every { dispatchers.default } returns testDispatcher
        val adapterWithV1Base = OpenAiCompatibleProvider(
            id = "v1-base",
            baseUrl = mockServer.url("/").toString().trimEnd('/') + "/v1",
            apiKeyProvider = { null },
            client = client,
            moshi = Moshi.Builder().add(Any::class.java, JsonTreeAdapter).build(),
            dispatchers = dispatchers,
        )

        assertTrue(adapterWithV1Base.listModels().isSuccess)
        assertEquals("/v1/models", mockServer.takeRequest().path)
    }

    @Test
    fun `listModels fails on 401 unauthorized`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = provider.listModels()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()!!
        assertTrue(error is OpenAiCompatibleProvider.HttpAdapterException)
        assertEquals(401, (error as OpenAiCompatibleProvider.HttpAdapterException).code)
    }

    @Test
    fun `listModels fails on 403 forbidden`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val result = provider.listModels()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as OpenAiCompatibleProvider.HttpAdapterException
        assertEquals(403, error.code)
    }

    @Test
    fun `listModels retries on 500 then fails after max attempts`() = runTest {
        repeat(3) {
            mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        }

        val result = provider.listModels()

        assertTrue(result.isFailure)
        assertEquals(3, mockServer.requestCount)
    }

    @Test
    fun `streamChat emits TokenDelta events from SSE stream`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
            ),
            model = "gpt-4o",
        )

        val events = provider.streamChat(request).toList()

        // The first SSE chunk carries an empty assistant role delta; skip empty tokens.
        val tokenDeltas = events.filterIsInstance<ChatStreamEvent.TokenDelta>()
            .filter { it.text.isNotEmpty() }
        assertEquals(2, tokenDeltas.size)
        assertEquals("Hello", tokenDeltas[0].text)
        assertEquals(" world", tokenDeltas[1].text)
    }

    @Test
    fun `streamChat emits Done event at end of stream`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
            ),
            model = "gpt-4o",
        )

        val events = provider.streamChat(request).toList()

        assertTrue(events.any { it is ChatStreamEvent.Done })
    }

    @Test
    fun `streamChat emits Usage event with token counts`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
            ),
            model = "gpt-4o",
        )

        val events = provider.streamChat(request).toList()

        val usage = events.filterIsInstance<ChatStreamEvent.Usage>()
        assertEquals(1, usage.size)
        assertEquals(20, usage[0].promptTokens)
        assertEquals(5, usage[0].completionTokens)
    }

    @Test
    fun `streamChat sends correct Authorization header`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
            ),
            model = "gpt-4o",
        )

        provider.streamChat(request).toList()

        val recordedRequest = mockServer.takeRequest()
        assertEquals("Bearer test-api-key", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun `streamChat sends correct model in request body`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Hello"),
            ),
            model = "gpt-4o",
        )

        provider.streamChat(request).toList()

        val recordedRequest = mockServer.takeRequest()
        val body = recordedRequest.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-4o\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"Hello\""))
    }

    @Test
    fun `streamChat includes system prompt when provided`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
            ),
            systemPrompt = "You are a helpful assistant.",
            model = "gpt-4o",
        )

        provider.streamChat(request).toList()

        val recordedRequest = mockServer.takeRequest()
        val body = recordedRequest.body.readUtf8()
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("You are a helpful assistant"))
    }

    @Test
    fun `streamChat emits Error event on HTTP failure`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
            ),
            model = "gpt-4o",
        )

        val events = provider.streamChat(request).toList()

        val errors = events.filterIsInstance<ChatStreamEvent.Error>()
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `streamChat emits ToolCallRequested from streamed tool_calls deltas`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream_tool_calls.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Weather in London?"),
            ),
            model = "gpt-4o",
            toolsAvailable = listOf(
                ToolDefinition(
                    name = "get_weather",
                    description = "Get the current weather",
                    parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                ),
            ),
        )

        val events = provider.streamChat(request).toList()

        // id/name arrive in the first chunk, arguments stream in the second — one event.
        val calls = events.filterIsInstance<ChatStreamEvent.ToolCallRequested>()
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertEquals("""{"city":"London"}""", calls[0].argsJson)
        assertTrue(events.any { it is ChatStreamEvent.Done })
    }

    @Test
    fun `streamChat sends tools in the request body when available`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val request = ChatRequest(
            conversationHistory = listOf(
                Message(conversationId = "c1", role = MessageRole.USER, content = "Weather in London?"),
            ),
            model = "gpt-4o",
            toolsAvailable = listOf(
                ToolDefinition(
                    name = "get_weather",
                    description = "Get the current weather",
                    parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                ),
            ),
        )

        provider.streamChat(request).toList()

        val body = mockServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\":[{\"type\":\"function\""))
        assertTrue(body.contains("\"name\":\"get_weather\""))
        assertTrue(body.contains("\"description\":\"Get the current weather\""))
        // The JSON-Schema string is embedded as an object, not as a nested string.
        assertTrue(body.contains("\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}"))
    }

    @Test
    fun `streamChat round-trips assistant tool_calls and tool results in the body`() = runTest {
        val sseBody = javaClass.classLoader!!.getResource("fixtures/chat_stream.txt")!!.readText()
        mockServer.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val history = listOf(
            Message(conversationId = "c1", role = MessageRole.USER, content = "Weather in London?"),
            Message(
                conversationId = "c1",
                role = MessageRole.ASSISTANT,
                content = "",
                toolCallId = "call_x",
                toolCallName = "get_weather",
                toolCallArgsJson = """{"city":"London"}""",
            ),
            Message(
                conversationId = "c1",
                role = MessageRole.TOOL,
                content = "Sunny, 21C",
                toolCallId = "call_x",
                toolCallName = "get_weather",
            ),
        )

        provider.streamChat(ChatRequest(conversationHistory = history, model = "gpt-4o")).toList()

        val body = mockServer.takeRequest().body.readUtf8()
        // Assistant turn echoes the tool call under the synthesized id.
        assertTrue(body.contains("\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_x\""))
        assertTrue(body.contains("\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\""))
        // Tool turn carries the observation with the same id.
        assertTrue(body.contains("\"role\":\"tool\",\"content\":\"Sunny, 21C\""))
        assertTrue(body.contains("\"tool_call_id\":\"call_x\""))
    }

    @Test
    fun `capabilities are correctly declared`() {
        assertTrue(provider.capabilities.supportsTools)
        assertTrue(provider.capabilities.supportsReasoning)
        assertEquals(128_000, provider.capabilities.maxContext)
    }
}
