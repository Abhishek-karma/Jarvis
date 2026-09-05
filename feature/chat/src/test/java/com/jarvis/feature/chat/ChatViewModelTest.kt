package com.jarvis.feature.chat

import app.cash.turbine.test
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.Conversation
import com.jarvis.core.common.DEFAULT_CONVERSATION_TITLE
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.database.repository.ConversationRepository
import com.jarvis.core.ml.LocalConnectivity
import com.jarvis.core.ml.LocalLlmProvider
import com.jarvis.core.ml.LocalLlmRuntime
import com.jarvis.core.ml.LocalModelSpec
import com.jarvis.core.ml.LocalModelState
import com.jarvis.core.ml.LocalModelStore
import com.jarvis.core.ml.OnDeviceEngine
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.ProviderCapabilities
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.network.sse.OpenAiCompatibleProvider
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: ChatViewModel
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var providerManager: ProviderManager
    private lateinit var providersFlow: MutableStateFlow<List<ProviderConfig>>
    private lateinit var messagesFlow: MutableStateFlow<List<Message>>
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var sttProvider: SttProvider
    private lateinit var ttsProvider: TtsProvider
    private lateinit var localModelStore: LocalModelStore
    private lateinit var localLlmRuntime: LocalLlmRuntime
    private lateinit var connectivity: LocalConnectivity
    private lateinit var localModelStateFlow: MutableStateFlow<LocalModelState>

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        conversationRepository = mockk(relaxed = true)
        providerManager = mockk(relaxed = true)
        providersFlow = MutableStateFlow(emptyList())
        messagesFlow = MutableStateFlow(emptyList())

        every { providerManager.providers } returns providersFlow

        audioRecorder = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)
        sttProvider = mockk(relaxed = true)
        ttsProvider = mockk(relaxed = true)
        localModelStore = mockk(relaxed = true)
        localLlmRuntime = mockk(relaxed = true)
        connectivity = mockk(relaxed = true)
        localModelStateFlow = MutableStateFlow(LocalModelState.NotDownloaded)
        every { localModelStore.status } returns localModelStateFlow
        every { connectivity.isOnline() } returns true

        // Default: no saved conversation ID → creates new
        val savedStateHandle = androidx.lifecycle.SavedStateHandle()

        viewModel =
            ChatViewModel(
                conversationRepository = conversationRepository,
                providerManager = providerManager,
                dispatchers =
                    com.jarvis.core.common
                        .DispatcherProvider(),
                audioRecorder = audioRecorder,
                audioPlayer = audioPlayer,
                sttProvider = sttProvider,
                ttsProvider = ttsProvider,
                toolRegistry = ToolRegistry(), // empty registry: agent tests keep to the answer path
                auditLogger = AuditLogger { },
                localModelStore = localModelStore,
                localLlmRuntime = localLlmRuntime,
                connectivity = connectivity,
                savedStateHandle = savedStateHandle,
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty messages and composer`() {
        val state = viewModel.uiState.value
        assertTrue(state.messages.isEmpty())
        assertEquals("", state.composerText)
        assertFalse(state.isStreaming)
    }

    @Test
    fun `sendMessage does nothing when composer is empty`() =
        runTest {
            viewModel.onTextChange("")
            viewModel.sendMessage()
            advanceUntilIdle()

            coVerify(exactly = 0) { conversationRepository.upsertMessage(any()) }
        }

    @Test
    fun `onTextChange updates composer text`() {
        viewModel.onTextChange("Hello")
        assertEquals("Hello", viewModel.uiState.value.composerText)
    }

    @Test
    fun `isSendingEnabled becomes false when no providers configured`() =
        runTest {
            providersFlow.value = emptyList()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSendingEnabled)
        }

    @Test
    fun `isSendingEnabled becomes true when providers are configured`() =
        runTest {
            val provider = ProviderConfig(id = "p1", name = "OpenAI", baseUrl = "https://api.openai.com/v1")
            providersFlow.value = listOf(provider)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSendingEnabled)
        }

    @Test
    fun `openConversationById loads the specified conversation`() =
        runTest {
            val conversation = Conversation(id = "conv-1", title = "Test Chat")
            coEvery { conversationRepository.getConversation("conv-1") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-1") } returns emptyFlow()

            viewModel.openConversationById("conv-1")
            advanceUntilIdle()

            assertEquals("conv-1", viewModel.uiState.value.conversationId)
            assertEquals("Test Chat", viewModel.uiState.value.conversationTitle)
        }

    @Test
    fun `createNewConversation creates a new conversation`() =
        runTest {
            coEvery { conversationRepository.upsertConversation(any()) } just Runs
            coEvery { conversationRepository.observeMessages(any()) } returns emptyFlow()

            viewModel.createNewConversation()
            advanceUntilIdle()

            coVerify { conversationRepository.upsertConversation(match { it.title == DEFAULT_CONVERSATION_TITLE }) }
            assertTrue(viewModel.uiState.value.conversationId != null)
        }

    @Test
    fun `first send retitles an untitled conversation from its message`() =
        runTest {
            val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
            every { provider.capabilities } returns ProviderCapabilities(supportsTools = false)
            coEvery { provider.listModels() } returns Result.success(emptyList())
            coEvery { provider.streamChat(any()) } returns flowOf(ChatStreamEvent.Done)
            coEvery { providerManager.adapterFor(any()) } returns provider

            // Fresh conversation: title still the default.
            val conversation = Conversation(id = "conv-title")
            coEvery { conversationRepository.getConversation("conv-title") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-title") } returns emptyFlow()
            coEvery { conversationRepository.upsertConversation(any()) } just Runs
            coEvery { conversationRepository.getMessages("conv-title") } returns emptyList()
            coEvery { conversationRepository.upsertMessage(any()) } just Runs
            coEvery { conversationRepository.renameConversation(any(), any()) } just Runs
            providersFlow.value =
                listOf(
                    ProviderConfig(
                        id = "p1",
                        name = "OpenAI",
                        baseUrl = "https://api.openai.com",
                        model = "gpt-4o-mini",
                        isDefault = true,
                    ),
                )

            viewModel.openConversationById("conv-title")
            advanceUntilIdle()
            viewModel.onTextChange("hello world")
            viewModel.sendMessage()
            advanceUntilIdle()

            coVerify { conversationRepository.renameConversation("conv-title", "hello world") }
            assertEquals("hello world", viewModel.uiState.value.conversationTitle)
        }

    @Test
    fun `send does not retitle a conversation that already has a custom title`() =
        runTest {
            val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
            every { provider.capabilities } returns ProviderCapabilities(supportsTools = false)
            coEvery { provider.listModels() } returns Result.success(emptyList())
            coEvery { provider.streamChat(any()) } returns flowOf(ChatStreamEvent.Done)
            coEvery { providerManager.adapterFor(any()) } returns provider

            val conversation = Conversation(id = "conv-named", title = "Custom name")
            coEvery { conversationRepository.getConversation("conv-named") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-named") } returns emptyFlow()
            coEvery { conversationRepository.getMessages("conv-named") } returns emptyList()
            coEvery { conversationRepository.upsertMessage(any()) } just Runs
            providersFlow.value =
                listOf(
                    ProviderConfig(
                        id = "p1",
                        name = "OpenAI",
                        baseUrl = "https://api.openai.com",
                        model = "gpt-4o-mini",
                        isDefault = true,
                    ),
                )

            viewModel.openConversationById("conv-named")
            advanceUntilIdle()
            viewModel.onTextChange("hello world")
            viewModel.sendMessage()
            advanceUntilIdle()

            coVerify(exactly = 0) { conversationRepository.renameConversation(any(), any()) }
            assertEquals("Custom name", viewModel.uiState.value.conversationTitle)
        }

    @Test
    fun `auto-title collapses whitespace and caps the length`() =
        runTest {
            val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
            every { provider.capabilities } returns ProviderCapabilities(supportsTools = false)
            coEvery { provider.listModels() } returns Result.success(emptyList())
            coEvery { provider.streamChat(any()) } returns flowOf(ChatStreamEvent.Done)
            coEvery { providerManager.adapterFor(any()) } returns provider

            val conversation = Conversation(id = "conv-long")
            coEvery { conversationRepository.getConversation("conv-long") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-long") } returns emptyFlow()
            coEvery { conversationRepository.getMessages("conv-long") } returns emptyList()
            coEvery { conversationRepository.upsertMessage(any()) } just Runs
            providersFlow.value =
                listOf(
                    ProviderConfig(
                        id = "p1",
                        name = "OpenAI",
                        baseUrl = "https://api.openai.com",
                        model = "gpt-4o-mini",
                        isDefault = true,
                    ),
                )

            viewModel.openConversationById("conv-long")
            advanceUntilIdle()
            viewModel.onTextChange("  line one\n\nline two\n\n" + "z".repeat(80))
            viewModel.sendMessage()
            advanceUntilIdle()

            // "line one line two " (18 chars) + 32 z's = the 50-char cap.
            coVerify {
                conversationRepository.renameConversation("conv-long", "line one line two " + "z".repeat(32))
            }
        }

    @Test
    fun `setRoutingOverride persists override to conversation`() =
        runTest {
            // Set up a conversation first
            val conversation =
                Conversation(id = "conv-r", title = "Routing Chat", routingOverride = RoutingOverride.AUTO)
            coEvery { conversationRepository.getConversation("conv-r") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-r") } returns emptyFlow()
            coEvery { conversationRepository.upsertConversation(any()) } just Runs

            viewModel.openConversationById("conv-r")
            advanceUntilIdle()

            // Change routing to CLOUD
            viewModel.setRoutingOverride(RoutingOverride.CLOUD)
            advanceUntilIdle()

            coVerify {
                conversationRepository.upsertConversation(
                    match { it.id == "conv-r" && it.routingOverride == RoutingOverride.CLOUD },
                )
            }
        }

    @Test
    fun `setRoutingOverride updates activeRoute via resolveRoute`() =
        runTest {
            val conversation = Conversation(id = "conv-r2", title = "Chat", routingOverride = RoutingOverride.AUTO)
            coEvery { conversationRepository.getConversation("conv-r2") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-r2") } returns emptyFlow()
            coEvery { conversationRepository.upsertConversation(any()) } just Runs

            viewModel.openConversationById("conv-r2")
            advanceUntilIdle()

            viewModel.setRoutingOverride(RoutingOverride.AUTO)
            advanceUntilIdle()
            // v0.1: AUTO resolves to CLOUD (no local LLM)
            assertEquals(RoutingOverride.CLOUD, viewModel.uiState.value.activeRoute)

            viewModel.setRoutingOverride(RoutingOverride.CLOUD)
            advanceUntilIdle()
            assertEquals(RoutingOverride.CLOUD, viewModel.uiState.value.activeRoute)

            viewModel.setRoutingOverride(RoutingOverride.LOCAL)
            advanceUntilIdle()
            // v0.1: LOCAL also resolves to CLOUD with fallback
            assertEquals(RoutingOverride.CLOUD, viewModel.uiState.value.activeRoute)
        }

    @Test
    fun `setRoutingOverride to LOCAL emits ShowNotice`() =
        runTest {
            val conversation = Conversation(id = "conv-r3", title = "Chat")
            coEvery { conversationRepository.getConversation("conv-r3") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-r3") } returns emptyFlow()
            coEvery { conversationRepository.upsertConversation(any()) } just Runs

            viewModel.openConversationById("conv-r3")
            advanceUntilIdle()

            viewModel.uiEvents.test {
                viewModel.setRoutingOverride(RoutingOverride.LOCAL)
                val event = awaitItem()
                assertTrue(event is ChatUiEvent.ShowNotice)
                assertTrue((event as ChatUiEvent.ShowNotice).message.contains("local", ignoreCase = true))
            }
        }

    @Test
    fun `setRoutingOverride to CLOUD does not emit notice`() =
        runTest {
            val conversation = Conversation(id = "conv-r4", title = "Chat")
            coEvery { conversationRepository.getConversation("conv-r4") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-r4") } returns emptyFlow()
            coEvery { conversationRepository.upsertConversation(any()) } just Runs

            viewModel.openConversationById("conv-r4")
            advanceUntilIdle()

            viewModel.uiEvents.test {
                viewModel.setRoutingOverride(RoutingOverride.CLOUD)
                expectNoEvents()
            }
        }

    @Test
    fun `openConversation restores routing override from conversation`() =
        runTest {
            val conversation = Conversation(id = "conv-r5", title = "Chat", routingOverride = RoutingOverride.LOCAL)
            coEvery { conversationRepository.getConversation("conv-r5") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-r5") } returns emptyFlow()

            viewModel.openConversationById("conv-r5")
            advanceUntilIdle()

            assertEquals(RoutingOverride.LOCAL, viewModel.uiState.value.routingOverride)
            assertEquals(RoutingOverride.CLOUD, viewModel.uiState.value.activeRoute) // fallback
        }

    @Test
    fun `default routing override is AUTO`() {
        assertEquals(RoutingOverride.AUTO, viewModel.uiState.value.routingOverride)
    }

    @Test
    fun `cancelStreaming marks STREAMING assistant message as STOPPED`() =
        runTest {
            val streamingMessage =
                Message(
                    id = "msg-stream",
                    conversationId = "conv-c",
                    role = MessageRole.ASSISTANT,
                    content = "Hello partial",
                    status = MessageStatus.STREAMING,
                )
            messagesFlow.value = listOf(streamingMessage)

            val conversation = Conversation(id = "conv-c")
            coEvery { conversationRepository.getConversation("conv-c") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-c") } answers { messagesFlow }
            coEvery { conversationRepository.upsertConversation(any()) } just Runs
            coEvery { conversationRepository.upsertMessage(any()) } just Runs

            viewModel.openConversationById("conv-c")
            advanceUntilIdle()

            // Verify the streaming message is present in UI state
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                MessageStatus.STREAMING,
                viewModel.uiState.value.messages[0]
                    .status,
            )

            viewModel.cancelStreaming()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isStreaming)
            coVerify {
                conversationRepository.upsertMessage(
                    match { it.id == "msg-stream" && it.status == MessageStatus.STOPPED },
                )
            }
        }

    @Test
    fun `cancelStreaming with no active stream is a no-op`() =
        runTest {
            viewModel.cancelStreaming()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isStreaming)
        }

    @Test
    fun `Jarvis prefix routes to agent mode and persists the final answer`() =
        runTest {
            val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
            every { provider.capabilities } returns ProviderCapabilities(supportsTools = true)
            coEvery { provider.listModels() } returns Result.success(emptyList())
            coEvery { provider.streamChat(any()) } returns
                flowOf(
                    ChatStreamEvent.TokenDelta("Battery is at 80%."),
                    ChatStreamEvent.Done,
                )
            coEvery { providerManager.adapterFor(any()) } returns provider

            val conversation = Conversation(id = "conv-agent", title = "Chat")
            coEvery { conversationRepository.getConversation("conv-agent") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-agent") } returns emptyFlow()
            coEvery { conversationRepository.upsertConversation(any()) } just Runs
            coEvery { conversationRepository.getMessages("conv-agent") } returns emptyList()
            coEvery { conversationRepository.upsertMessage(any()) } just Runs
            providersFlow.value =
                listOf(
                    ProviderConfig(
                        id = "p1",
                        name = "OpenAI",
                        baseUrl = "https://api.openai.com",
                        model = "gpt-4o-mini",
                        isDefault = true,
                    ),
                )

            viewModel.openConversationById("conv-agent")
            advanceUntilIdle()
            viewModel.onTextChange("Jarvis, check the battery")
            viewModel.sendMessage()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isStreaming)
            assertFalse(viewModel.uiState.value.isAgentRunning)
            assertFalse(viewModel.uiState.value.pendingConfirmation != null)
            coVerify {
                conversationRepository.upsertMessage(
                    match {
                        it.role == MessageRole.ASSISTANT &&
                            it.status == MessageStatus.COMPLETE &&
                            it.content == "Battery is at 80%."
                    },
                )
            }
        }

    @Test
    fun `sendMessage aborts with an error when no model resolves`() =
        runTest {
            val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
            every { provider.capabilities } returns ProviderCapabilities(supportsTools = false)
            coEvery { provider.listModels() } returns Result.success(emptyList())
            coEvery { provider.streamChat(any()) } returns flowOf(ChatStreamEvent.Done)
            coEvery { providerManager.adapterFor(any()) } returns provider

            val conversation = Conversation(id = "conv-nomodel", title = "Chat")
            coEvery { conversationRepository.getConversation("conv-nomodel") } returns conversation
            coEvery { conversationRepository.observeMessages("conv-nomodel") } returns emptyFlow()
            coEvery { conversationRepository.upsertConversation(any()) } just Runs
            coEvery { conversationRepository.upsertMessage(any()) } just Runs
            providersFlow.value =
                listOf(
                    ProviderConfig(
                        id = "p1",
                        name = "Local",
                        baseUrl = "http://10.0.2.2:11434",
                        isDefault = true,
                    ),
                )

            viewModel.openConversationById("conv-nomodel")
            advanceUntilIdle()

            viewModel.uiEvents.test {
                viewModel.onTextChange("hello")
                viewModel.sendMessage()
                val event = awaitItem()
                assertTrue(event is ChatUiEvent.ShowError)
                assertTrue((event as ChatUiEvent.ShowError).message.contains("No model"))
            }

            // Abort leaves the composer text intact for a retry and persists nothing.
            assertEquals("hello", viewModel.uiState.value.composerText)
            assertFalse(viewModel.uiState.value.isStreaming)
            coVerify(exactly = 0) { conversationRepository.upsertMessage(any()) }
        }

    @Test
    fun `agent run renders each canvas milestone with completed checkmarks`() =
        runTest {
            val provider =
                agentProvider(
                    listOf(
                        flowOf(
                            ChatStreamEvent.ToolCallRequested(name = "current_time", argsJson = "{}"),
                            ChatStreamEvent.Done,
                        ),
                        flowOf(ChatStreamEvent.TokenDelta("It is 12:00 UTC."), ChatStreamEvent.Done),
                    ),
                )
            viewModelWith(tool("current_time"))
            openAgentConversation()

            viewModel.onTextChange("Jarvis, what time is it?")
            viewModel.sendMessage()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isAgentRunning)
            assertEquals(listOf("current_time done"), state.agentSteps.map { it.text })
            assertTrue(state.agentSteps.isNotEmpty())
            assertTrue(state.agentSteps.none { it.state == AgentStepState.RUNNING })
            coVerify {
                conversationRepository.upsertMessage(
                    match { it.role == MessageRole.ASSISTANT && it.content == "It is 12:00 UTC." },
                )
            }
        }

    @Test
    fun `sensitive tool parks the run at the confirmation until allowed`() =
        runTest {
            val provider =
                agentProvider(
                    listOf(
                        flowOf(
                            ChatStreamEvent.ToolCallRequested(name = "send_it", argsJson = "{\"text\":\"hi\"}"),
                            ChatStreamEvent.Done,
                        ),
                        flowOf(ChatStreamEvent.TokenDelta("Sent."), ChatStreamEvent.Done),
                    ),
                )
            viewModelWith(tool("send_it", tier = PermissionTier.SENSITIVE))
            openAgentConversation()

            viewModel.onTextChange("Jarvis, send it")
            viewModel.sendMessage()
            advanceUntilIdle()

            // Parked: confirmation surfaced, running row renamed to the approval prompt.
            var state = viewModel.uiState.value
            assertTrue(state.isAgentRunning)
            assertEquals("send_it", state.pendingConfirmation?.toolName)
            assertEquals("Needs your approval: send_it", state.agentSteps.last().text)
            assertEquals(AgentStepState.RUNNING, state.agentSteps.last().state)

            viewModel.respondToConfirmation(allow = true)
            advanceUntilIdle()

            state = viewModel.uiState.value
            assertFalse(state.isAgentRunning)
            assertEquals(null, state.pendingConfirmation)
            assertEquals("send_it done", state.agentSteps.last().text)
            assertEquals(AgentStepState.DONE, state.agentSteps.last().state)
            coVerify {
                conversationRepository.upsertMessage(
                    match { it.role == MessageRole.ASSISTANT && it.content == "Sent." },
                )
            }
        }

    @Test
    fun `denying a sensitive tool halts the run and persists no assistant answer`() =
        runTest {
            val provider =
                agentProvider(
                    listOf(
                        flowOf(
                            ChatStreamEvent.ToolCallRequested(name = "send_it", argsJson = "{\"text\":\"hi\"}"),
                            ChatStreamEvent.Done,
                        ),
                    ),
                )
            viewModelWith(tool("send_it", tier = PermissionTier.SENSITIVE))
            openAgentConversation()

            viewModel.onTextChange("Jarvis, send it")
            viewModel.sendMessage()
            advanceUntilIdle()

            viewModel.respondToConfirmation(allow = false)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isAgentRunning)
            assertEquals(null, state.pendingConfirmation)
            assertEquals("Denied send_it", state.agentSteps.last().text)
            assertEquals(AgentStepState.DONE, state.agentSteps.last().state)
            coVerify(exactly = 0) {
                conversationRepository.upsertMessage(match { it.role == MessageRole.ASSISTANT })
            }
        }

    @Test
    fun `AUTO offline with an installed model routes to the on-device engine`() =
        runTest {
            every { connectivity.isOnline() } returns false
            installLocalModel(partials = listOf("Local answer"))
            openAgentConversation()
            // The real repo returns the just-persisted user row; the local engine needs non-blank history.
            coEvery {
                conversationRepository.getMessages("conv-agent")
            } returns
                listOf(Message(id = "u1", conversationId = "conv-agent", role = MessageRole.USER, content = "hello"))

            viewModel.onTextChange("hello")
            viewModel.sendMessage()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RoutingOverride.LOCAL, state.activeRoute)
            assertFalse(state.isStreaming)
            coVerify {
                conversationRepository.upsertMessage(
                    match {
                        it.role == MessageRole.ASSISTANT &&
                            it.status == MessageStatus.COMPLETE &&
                            it.content == "Local answer"
                    },
                )
            }
        }

    @Test
    fun `AUTO online with an installed model still uses the cloud provider`() =
        runTest {
            val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
            every { provider.capabilities } returns ProviderCapabilities(supportsTools = false)
            coEvery { provider.streamChat(any()) } returns flowOf(ChatStreamEvent.Done)
            coEvery { providerManager.adapterFor(any()) } returns provider
            every { connectivity.isOnline() } returns true
            installLocalModel()
            openAgentConversation()

            viewModel.onTextChange("hello")
            viewModel.sendMessage()
            advanceUntilIdle()

            assertEquals(RoutingOverride.CLOUD, viewModel.uiState.value.activeRoute)
            coVerify(exactly = 0) { localLlmRuntime.currentProvider() }
        }

    @Test
    fun `forcing LOCAL without a model falls back to cloud with a notice`() =
        runTest {
            val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
            every { provider.capabilities } returns ProviderCapabilities(supportsTools = false)
            coEvery { provider.streamChat(any()) } returns flowOf(ChatStreamEvent.Done)
            coEvery { providerManager.adapterFor(any()) } returns provider
            // Store stays NotDownloaded (default), online so the cloud path is real.
            every { connectivity.isOnline() } returns true
            openAgentConversation()

            viewModel.uiEvents.test {
                viewModel.setRoutingOverride(RoutingOverride.LOCAL)
                viewModel.onTextChange("hello")
                viewModel.sendMessage()
                val event = awaitItem()
                assertTrue(event is ChatUiEvent.ShowNotice)
                assertTrue((event as ChatUiEvent.ShowNotice).message.contains("local", ignoreCase = true))
            }
            advanceUntilIdle()
            assertEquals(RoutingOverride.CLOUD, viewModel.uiState.value.activeRoute)
            coVerify(exactly = 0) { localLlmRuntime.currentProvider() }
        }

    /** A ChatViewModel whose ToolRegistry carries the given tools. */
    private fun viewModelWith(vararg tools: Tool) {
        val registry = ToolRegistry()
        tools.forEach { registry.register(it) }
        viewModel =
            ChatViewModel(
                conversationRepository = conversationRepository,
                providerManager = providerManager,
                dispatchers =
                    com.jarvis.core.common
                        .DispatcherProvider(),
                audioRecorder = audioRecorder,
                audioPlayer = audioPlayer,
                sttProvider = sttProvider,
                ttsProvider = ttsProvider,
                toolRegistry = registry,
                auditLogger = AuditLogger { },
                localModelStore = localModelStore,
                localLlmRuntime = localLlmRuntime,
                connectivity = connectivity,
                savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            )
    }

    /** Provider that emits the given stream per engine iteration, one per ReAct loop call. */
    private fun agentProvider(streams: List<Flow<ChatStreamEvent>>): OpenAiCompatibleProvider {
        val provider = mockk<OpenAiCompatibleProvider>(relaxed = true)
        every { provider.capabilities } returns ProviderCapabilities(supportsTools = true)
        coEvery { provider.listModels() } returns Result.success(emptyList())
        coEvery { provider.streamChat(any()) } returnsMany streams
        coEvery { providerManager.adapterFor(any()) } returns provider
        return provider
    }

    /** Open the agent conversation with a default cloud provider configured. */
    private fun TestScope.openAgentConversation() {
        val conversation = Conversation(id = "conv-agent", title = "Chat")
        coEvery { conversationRepository.getConversation("conv-agent") } returns conversation
        coEvery { conversationRepository.observeMessages("conv-agent") } returns emptyFlow()
        coEvery { conversationRepository.upsertConversation(any()) } just Runs
        coEvery { conversationRepository.getMessages("conv-agent") } returns emptyList()
        coEvery { conversationRepository.upsertMessage(any()) } just Runs
        providersFlow.value =
            listOf(
                ProviderConfig(
                    id = "p1",
                    name = "OpenAI",
                    baseUrl = "https://api.openai.com",
                    model = "gpt-4o-mini",
                    isDefault = true,
                ),
            )
        viewModel.openConversationById("conv-agent")
        advanceUntilIdle()
    }

    /** Make the local store report an installed model and the runtime return a fake-backed provider. */
    private fun installLocalModel(partials: List<String> = listOf("Local answer")) {
        val spec =
            LocalModelSpec(
                id = "gemma-2-2b-it",
                displayName = "Gemma 2 2B",
                fileName = "gemma.task",
            )
        localModelStateFlow.value = LocalModelState.Ready(spec, File("model.task"))
        coEvery { localLlmRuntime.currentProvider() } returns
            LocalLlmProvider(id = "local-gemma", spec = spec, engine = FakeLocalEngine(partials))
    }

    /** Test engine replaying partials, mirroring the module-level fake. */
    private class FakeLocalEngine(
        private val partials: List<String>,
    ) : OnDeviceEngine {
        override suspend fun generate(
            prompt: String,
            onPartial: (String) -> Unit,
            onDone: () -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            partials.forEach(onPartial)
            onDone()
        }

        override fun close() = Unit
    }

    /** Minimal test tool returning [result]. */
    private fun tool(
        name: String,
        tier: PermissionTier = PermissionTier.READ_ONLY,
        result: ToolResult = ToolResult(success = true, observationText = "ok"),
    ) = object : Tool {
        override val name = name
        override val description = "test tool $name"
        override val parametersSchemaJson = """{"type":"object","properties":{}}"""
        override val tier = tier

        override suspend fun execute(argsJson: String): ToolResult = result
    }
}
