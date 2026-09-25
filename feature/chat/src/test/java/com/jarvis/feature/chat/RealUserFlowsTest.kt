package com.jarvis.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.GoalEngine
import com.jarvis.core.agent.GoalEvent
import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.common.Conversation
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.ModelInfo
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.database.repository.ConversationRepository
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.preferences.UserPreferencesRepository
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.VoiceStateMachine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealUserFlowsTest {
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = DispatcherProvider()

    private lateinit var conversationRepository: ConversationRepository
    private lateinit var providerManager: ProviderManager
    private lateinit var providersFlow: MutableStateFlow<List<ProviderConfig>>
    private lateinit var messagesFlow: MutableStateFlow<List<Message>>
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var sttProvider: SttProvider
    private lateinit var ttsProvider: TtsProvider
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var auditLogger: AuditLogger
    private lateinit var conversationContextManager: ConversationContextManager
    private lateinit var goalEngine: GoalEngine
    private lateinit var mockProviderAdapter: LlmProvider

    private val activeProviderConfig = ProviderConfig(
        id = "gemini-test",
        name = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com",
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        conversationRepository = mockk(relaxed = true)
        providerManager = mockk(relaxed = true)
        providersFlow = MutableStateFlow(listOf(activeProviderConfig))
        messagesFlow = MutableStateFlow(emptyList())

        every { providerManager.providers } returns providersFlow
        every { conversationRepository.observeMessages(any()) } returns messagesFlow

        mockProviderAdapter = mockk(relaxed = true)
        coEvery { mockProviderAdapter.listModels() } returns Result.success(listOf(ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash")))
        every { providerManager.adapterFor(any()) } returns mockProviderAdapter

        audioRecorder = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)
        sttProvider = mockk(relaxed = true)
        ttsProvider = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        every { userPreferences.agentStepCap } returns MutableStateFlow(15)
        every { userPreferences.thinkMode } returns MutableStateFlow(ThinkMode.AUTO)
        every { userPreferences.chatMode } returns MutableStateFlow(com.jarvis.core.preferences.ChatMode.CLOUD)
        every { userPreferences.memoryEnabled } returns MutableStateFlow(true)
        every { userPreferences.planFirstMode } returns MutableStateFlow(false)

        toolRegistry = ToolRegistry()
        auditLogger = AuditLogger { }
        conversationContextManager = mockk(relaxed = true)
        coEvery { conversationContextManager.buildMemoryContext(any(), any()) } returns null
        every { conversationContextManager.buildAssistantSystemPrompt(any(), any(), any(), any()) } returns "System prompt"

        goalEngine = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(conversationId: String? = null): ChatViewModel {
        val voiceManager = ChatVoiceManager(audioRecorder, audioPlayer, sttProvider, ttsProvider, VoiceStateMachine())
        val handle = SavedStateHandle().apply {
            if (conversationId != null) set("conversationId", conversationId)
        }
        return ChatViewModel(
            conversationRepository = conversationRepository,
            providerManager = providerManager,
            dispatchers = dispatchers,
            voiceManager = voiceManager,
            toolRegistry = toolRegistry,
            userPreferences = userPreferences,
            conversationContextManager = conversationContextManager,
            goalEngine = goalEngine,
            savedStateHandle = handle,
        )
    }

    @Test
    fun `Test 1 - User says Hello and receives normal streamed response`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta("Hello! ")))
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta("How can I assist you today?")))
            emit(GoalEvent.AgentEvent(AgentEvent.FinalAnswer("Hello! How can I assist you today?")))
            emit(GoalEvent.Completed("goal-1", summary = "Hello! How can I assist you today?"))
        }

        viewModel.onTextChange("Hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(AgentStatus.COMPLETED, state.agentStatus)
        coVerify { conversationRepository.upsertMessage(match { it.role == MessageRole.USER && it.content == "Hello" }) }
        coVerify { conversationRepository.upsertMessage(match { it.role == MessageRole.ASSISTANT && it.content.contains("How can I assist you today?") }) }
    }

    @Test
    fun `Test 2 - User asks general knowledge question with no tools`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val answer = "The speed of light in vacuum is approximately 299,792,458 meters per second."
        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta(answer)))
            emit(GoalEvent.AgentEvent(AgentEvent.FinalAnswer(answer)))
            emit(GoalEvent.Completed("goal-2", summary = answer))
        }

        viewModel.onTextChange("What is the speed of light?")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(AgentStatus.COMPLETED, state.agentStatus)
        coVerify { conversationRepository.upsertMessage(match { it.role == MessageRole.ASSISTANT && it.content == answer }) }
    }

    @Test
    fun `Test 3 - User requests tool action - LLM calls tool, gets result, and responds`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.AgentEvent(AgentEvent.ToolRequested("get_battery_status", "{}", PermissionTier.READ_ONLY)))
            emit(GoalEvent.AgentEvent(AgentEvent.ToolExecuting("get_battery_status")))
            emit(GoalEvent.AgentEvent(AgentEvent.ToolExecuted("get_battery_status", success = true, observationText = "Battery level: 85%, Charging: false")))
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta("Your device battery is at 85% and is not currently charging.")))
            emit(GoalEvent.AgentEvent(AgentEvent.FinalAnswer("Your device battery is at 85% and is not currently charging.")))
            emit(GoalEvent.Completed("goal-3", summary = "Your device battery is at 85% and is not currently charging."))
        }

        viewModel.onTextChange("Check my battery")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AgentStatus.COMPLETED, state.agentStatus)
        assertFalse(state.isStreaming)
        coVerify { conversationRepository.upsertMessage(match { it.role == MessageRole.ASSISTANT && it.content.contains("85%") }) }
    }

    @Test
    fun `Test 4 - Tool fails - user receives clear error and app remains usable`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.AgentEvent(AgentEvent.ToolRequested("device_action", "{}", PermissionTier.SENSITIVE)))
            emit(GoalEvent.AgentEvent(AgentEvent.ToolExecuted("device_action", success = false, observationText = "Permission denied for action", errorCode = ErrorCode.PERMISSION_REQUIRED)))
            emit(GoalEvent.Failed("goal-4", "PERMISSION_REQUIRED", "Permission denied for action"))
        }

        viewModel.onTextChange("Turn off wifi")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(AgentStatus.FAILED, state.agentStatus)
        assertNotNull(state.agentFailureReason)

        // App remains fully usable for a subsequent message
        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta("Sure! What else can I help with?")))
            emit(GoalEvent.Completed("goal-4b", summary = "Sure! What else can I help with?"))
        }

        viewModel.onTextChange("Never mind, how are you?")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(AgentStatus.COMPLETED, viewModel.uiState.value.agentStatus)
    }

    @Test
    fun `Test 5 - Network fails - graceful error and retry`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.Failed("goal-5", "NETWORK_UNAVAILABLE", "Network connection failed. Please verify your internet connection."))
        }

        viewModel.onTextChange("Search web for news")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(AgentStatus.FAILED, state.agentStatus)
        assertEquals("No internet connection available.", state.agentFailureReason)
    }

    @Test
    fun `Test 6 - User cancels generation - execution stops cleanly`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta("Starting extensive computation...")))
            kotlinx.coroutines.delay(10000)
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta("Finished.")))
        }

        viewModel.onTextChange("Perform large calculation")
        viewModel.sendMessage()
        testDispatcher.scheduler.advanceTimeBy(100)

        assertTrue(viewModel.uiState.value.isStreaming)

        // User taps Cancel
        viewModel.cancelStreaming()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertFalse(state.isAgentRunning)
        assertEquals(AgentStatus.CANCELLED, state.agentStatus)
    }

    @Test
    fun `Test 7 - App is recreated after conversation - conversation remains consistent`() = runTest(testDispatcher) {
        val existingConversationId = "conv-persisted-123"
        val existingMessages = listOf(
            Message(id = "m1", conversationId = existingConversationId, role = MessageRole.USER, content = "Remember my code is 42", createdAt = 1000L),
            Message(id = "m2", conversationId = existingConversationId, role = MessageRole.ASSISTANT, content = "Got it, code 42 remembered.", createdAt = 2000L),
        )

        messagesFlow.value = existingMessages
        coEvery { conversationRepository.getConversation(existingConversationId) } returns Conversation(
            id = existingConversationId,
            title = "Secret code discussion",
            createdAt = 1000L,
            updatedAt = 2000L,
        )

        // Recreate ViewModel with existing conversation ID
        val recreatedViewModel = createViewModel(existingConversationId)
        advanceUntilIdle()

        val state = recreatedViewModel.uiState.value
        assertEquals(existingConversationId, state.conversationId)
        assertEquals(2, state.messages.size)
        assertEquals("Remember my code is 42", state.messages[0].content)
        assertEquals("Got it, code 42 remembered.", state.messages[1].content)
    }

    @Test
    fun `Test 8 - Rapid repeated request - no accidental double execution`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.AgentEvent(AgentEvent.TextDelta("Response text")))
            emit(GoalEvent.Completed("goal-8", summary = "Response text"))
        }

        viewModel.onTextChange("Send once")
        viewModel.sendMessage()
        // Immediate second tap while still streaming
        viewModel.sendMessage()
        advanceUntilIdle()

        // Should only trigger goal execution once
        coVerify(exactly = 1) { goalEngine.executeGoal(any()) }
    }

    @Test
    fun `Test 9 - Malformed or unexpected response - no crash or infinite loop`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { goalEngine.executeGoal(any()) } returns flow {
            emit(GoalEvent.Failed("goal-9", "PARSE_ERROR", "Received unexpected JSON structure from model"))
        }

        viewModel.onTextChange("Trigger edge case")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(AgentStatus.FAILED, state.agentStatus)
        assertEquals("Received unexpected JSON structure from model", state.agentFailureReason)
    }

    @Test
    fun `Test 10 - Fresh installation without providers - guides user to Settings`() = runTest(testDispatcher) {
        // Empty providers on fresh install
        providersFlow.value = emptyList()

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSendingEnabled)

        viewModel.uiEvents.test {
            viewModel.onTextChange("Hello on fresh install")
            viewModel.sendMessage()

            val event = awaitItem()
            assertTrue(event is ChatUiEvent.ShowNotice)
            assertTrue((event as ChatUiEvent.ShowNotice).message.contains("Settings"))

            // User configures provider in settings
            providersFlow.value = listOf(activeProviderConfig)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSendingEnabled)
        }
    }
}
