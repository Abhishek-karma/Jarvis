package com.jarvis.core.network.gemini

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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance


@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GeminiProviderTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var provider: GeminiProvider
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockServer = MockWebServer()
        mockServer.start()

        val moshi =
            Moshi
                .Builder()
                .add(Any::class.java, JsonTreeAdapter)
                .build()
        val dispatchers = mockk<com.jarvis.core.common.DispatcherProvider>()
        every { dispatchers.main } returns testDispatcher
        every { dispatchers.io } returns testDispatcher
        every { dispatchers.default } returns testDispatcher

        provider =
            GeminiProvider(
                id = "test-gemini",
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

    private fun sseResponse(fixture: String): MockResponse =
        MockResponse()
            .setBody(fixture)
            .setHeader("Content-Type", "text/event-stream")

    private fun textRequest() =
        ChatRequest(
            conversationHistory =
                listOf(
                    Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
                ),
            model = "gemini-2.0-flash",
        )

    @Test
    fun `streamChat emits TokenDelta events from SSE stream`() =
        runTest {
            val fixture = javaClass.classLoader!!.getResource("fixtures/gemini_stream_text.txt")!!.readText()
            mockServer.enqueue(sseResponse(fixture))

            val events = provider.streamChat(textRequest()).toList()

            val tokenDeltas =
                events
                    .filterIsInstance<ChatStreamEvent.TokenDelta>()
                    .filter { it.text.isNotEmpty() }
            assertEquals(1, tokenDeltas.size)
            assertEquals("Hello", tokenDeltas[0].text)
            assertTrue(events.any { it is ChatStreamEvent.Done })
        }

    @Test
    fun `streamChat sends the key in the header, never the URL`() =
        runTest {
            val fixture = javaClass.classLoader!!.getResource("fixtures/gemini_stream_text.txt")!!.readText()
            mockServer.enqueue(sseResponse(fixture))

            provider.streamChat(textRequest()).toList()

            val recorded = mockServer.takeRequest()
            assertTrue(recorded.path!!.contains(":streamGenerateContent"))
            assertFalse(recorded.path!!.contains("key="))
            assertEquals("test-api-key", recorded.getHeader("x-goog-api-key"))
        }

    @Test
    fun `listModels sends the key in the header, never the URL`() =
        runTest {
            mockServer.enqueue(
                MockResponse()
                    .setBody("""{"models":[]}""")
                    .setHeader("Content-Type", "application/json"),
            )

            provider.listModels()

            val recorded = mockServer.takeRequest()
            assertFalse(recorded.path!!.contains("key="))
            assertEquals("test-api-key", recorded.getHeader("x-goog-api-key"))
        }

    @Test
    fun `streamChat emits ToolCallRequested from a functionCall part`() =
        runTest {
            val fixture = javaClass.classLoader!!.getResource("fixtures/gemini_stream_function_call.txt")!!.readText()
            mockServer.enqueue(sseResponse(fixture))

            val events = provider.streamChat(textRequest()).toList()

            val calls = events.filterIsInstance<ChatStreamEvent.ToolCallRequested>()
            assertEquals(1, calls.size)
            assertEquals("get_weather", calls[0].name)
            assertTrue(calls[0].argsJson.contains("\"city\":\"London\""))
            assertTrue(events.any { it is ChatStreamEvent.Done })
        }

    @Test
    fun `streamChat sends functionDeclarations in the body`() =
        runTest {
            val fixture = javaClass.classLoader!!.getResource("fixtures/gemini_stream_text.txt")!!.readText()
            mockServer.enqueue(sseResponse(fixture))

            val request =
                ChatRequest(
                    conversationHistory =
                        listOf(
                            Message(conversationId = "c1", role = MessageRole.USER, content = "Weather in London?"),
                        ),
                    model = "gemini-2.0-flash",
                    toolsAvailable =
                        listOf(
                            ToolDefinition(
                                name = "get_weather",
                                description = "Get the current weather",
                                parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                            ),
                        ),
                )

            provider.streamChat(request).toList()

            val body = mockServer.takeRequest().body.readUtf8()

            assertTrue(body.contains("\"tools\":[{\"functionDeclarations\":[{\"name\":\"get_weather\""))
            assertTrue(
                body.contains("\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}"),
            )
        }

    @Test
    fun `streamChat round-trips functionCall and functionResponse turns in the body`() =
        runTest {
            val fixture = javaClass.classLoader!!.getResource("fixtures/gemini_stream_text.txt")!!.readText()
            mockServer.enqueue(sseResponse(fixture))

            val history =
                listOf(
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

            provider.streamChat(ChatRequest(conversationHistory = history, model = "gemini-2.0-flash")).toList()

            val body = mockServer.takeRequest().body.readUtf8()

            assertTrue(body.contains("\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"London\"}}"))
            assertTrue(
                body.contains(
                    "\"functionResponse\":{\"name\":\"get_weather\",\"response\":{\"result\":\"Sunny, 21C\"}}",
                ),
            )
            assertTrue(body.contains("\"role\":\"user\""))
        }

    @Test
    fun `think mode adds thinking config to the request body`() =
        runTest {
            val fixture = javaClass.classLoader!!.getResource("fixtures/gemini_stream_text.txt")!!.readText()
            mockServer.enqueue(sseResponse(fixture))

            provider.streamChat(
                ChatRequest(
                    conversationHistory =
                        listOf(
                            Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
                        ),
                    model = "gemini-2.0-flash",
                    reasoningRequested = true,
                ),
            ).toList()

            val body = mockServer.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":4096}"))
        }

    @Test
    fun `no think mode omits thinking config from the request body`() =
        runTest {
            val fixture = javaClass.classLoader!!.getResource("fixtures/gemini_stream_text.txt")!!.readText()
            mockServer.enqueue(sseResponse(fixture))

            provider.streamChat(
                ChatRequest(
                    conversationHistory =
                        listOf(
                            Message(conversationId = "c1", role = MessageRole.USER, content = "Hi"),
                        ),
                    model = "gemini-2.0-flash",
                ),
            ).toList()

            val body = mockServer.takeRequest().body.readUtf8()
            assertFalse(body.contains("thinkingConfig"))
        }

    @Test
    fun `capabilities are correctly declared`() {
        assertTrue(provider.capabilities.supportsTools)
        assertTrue(provider.capabilities.vision)
        assertEquals(1_000_000, provider.capabilities.maxContext)
    }
}
