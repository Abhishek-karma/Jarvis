package com.jarvis.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.agent.AgentEngine
import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentTrigger
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.common.Conversation
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.database.repository.ConversationRepository
import com.jarvis.core.navigation.Routes
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.ml.LocalConnectivity
import com.jarvis.core.ml.LocalLlmRuntime
import com.jarvis.core.ml.LocalModelState
import com.jarvis.core.ml.LocalModelStore
import com.jarvis.core.voice.AudioFormat
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.TtsVoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val conversationId: String? = null,
    val conversationTitle: String = "New chat",
    val messages: List<Message> = emptyList(),
    val composerText: String = "",
    val isStreaming: Boolean = false,
    val isSendingEnabled: Boolean = true,
    /** Per-chat routing override (Auto / Local / Cloud). Persists with the conversation. */
    val routingOverride: RoutingOverride = RoutingOverride.AUTO,
    /** Effective route for the current response, shown on the route badge. */
    val activeRoute: RoutingOverride = RoutingOverride.CLOUD,
    /** Voice recording state. */
    val isRecording: Boolean = false,
    /** Transcribing audio to text. */
    val isTranscribing: Boolean = false,
    /** TTS audio playback state. */
    val isPlayingAudio: Boolean = false,
    /** A ReAct agent run is in progress. */
    val isAgentRunning: Boolean = false,
    /** Sensitive-tier tool awaiting an explicit user decision. */
    val pendingConfirmation: AgentConfirmation? = null,
    /** Live step log rendered by the Agent Canvas. */
    val agentSteps: List<AgentStep> = emptyList(),
)

sealed interface ChatUiEvent {
    data class ShowError(val message: String) : ChatUiEvent
    data class ShowNotice(val message: String) : ChatUiEvent
}

/** A Sensitive-tier tool call parked until the user taps Allow/Deny. */
data class AgentConfirmation(
    val toolName: String,
    val argsJson: String,
)

enum class AgentStepState { RUNNING, DONE }

/** One line in the Agent Canvas step list. */
data class AgentStep(
    val text: String,
    val state: AgentStepState = AgentStepState.RUNNING,
)

/**
 * MVI ViewModel for the chat screen: one immutable UiState, events flow up, streaming
 * responses update the assistant message as tokens arrive.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val providerManager: ProviderManager,
    private val dispatchers: DispatcherProvider,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val sttProvider: SttProvider,
    private val ttsProvider: TtsProvider,
    private val toolRegistry: ToolRegistry,
    private val auditLogger: AuditLogger,
    private val localModelStore: LocalModelStore,
    private val localLlmRuntime: LocalLlmRuntime,
    private val connectivity: LocalConnectivity,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<ChatUiEvent>()
    val uiEvents: SharedFlow<ChatUiEvent> = _uiEvents.asSharedFlow()

    private var activeProvider: ProviderConfig? = null

    /** Handle to the in-flight streaming request, used by [cancelStreaming]. */
    private var streamJob: Job? = null

    /** Bridges the engine's [ConfirmationGate] to the UI: completed by [respondToConfirmation]. */
    private var pendingGate: CompletableDeferred<Boolean>? = null

    init {
        viewModelScope.launch(dispatchers.main) {
            val conversationId = savedStateHandle.get<String>(Routes.CHAT_ARG_CONVERSATION_ID)
            openConversation(conversationId)
        }

        viewModelScope.launch(dispatchers.main) {
            providerManager.providers.collectLatest { providers ->
                activeProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
                _uiState.update { it.copy(isSendingEnabled = activeProvider != null) }
            }
        }
    }

    private suspend fun openConversation(conversationId: String?) {
        val conversation =
            conversationId?.let { conversationRepository.getConversation(it) } ?: createConversation()

        _uiState.update {
            it.copy(
                conversationId = conversation.id,
                conversationTitle = conversation.title,
                routingOverride = conversation.routingOverride,
                activeRoute = effectiveRoute(conversation.routingOverride),
            )
        }
        conversationRepository.observeMessages(conversation.id).collectLatest { messages ->
            _uiState.update { state -> state.copy(messages = messages) }
        }
    }

    /** Open a conversation selected from the History drawer. */
    fun openConversationById(conversationId: String) {
        viewModelScope.launch(dispatchers.main) {
            openConversation(conversationId)
        }
    }

    /**
     * Change the per-chat routing override (Auto / Always Local / Always Cloud).
     * Persists with the conversation so it survives process death.
     */
    fun setRoutingOverride(override: RoutingOverride) {
        val conversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch(dispatchers.main) {
            conversationRepository.getConversation(conversationId)?.let { current ->
                conversationRepository.upsertConversation(current.copy(routingOverride = override))
            }
            _uiState.update {
                it.copy(routingOverride = override, activeRoute = effectiveRoute(override))
            }
            if (override == RoutingOverride.LOCAL && !localModelReady) {
                _uiEvents.emit(ChatUiEvent.ShowNotice(LOCAL_UNAVAILABLE_NOTICE))
            }
        }
    }

    /**
     * Smart routing:
     *  LOCAL → on-device when a model is installed, cloud otherwise;
     *  CLOUD → cloud;
     *  AUTO  → cloud when online, on-device when offline with a model installed.
     */
    private fun effectiveRoute(override: RoutingOverride): RoutingOverride = when (override) {
        RoutingOverride.CLOUD -> RoutingOverride.CLOUD
        RoutingOverride.LOCAL -> if (localModelReady) RoutingOverride.LOCAL else RoutingOverride.CLOUD
        RoutingOverride.AUTO ->
            if (!connectivity.isOnline() && localModelReady) RoutingOverride.LOCAL else RoutingOverride.CLOUD
    }

    /** True when an on-device model file is installed and ready (used for routing decisions). */
    private val localModelReady: Boolean
        get() = runCatching { localModelStore.status.value is LocalModelState.Ready }.getOrDefault(false)

    /** Create a fresh conversation and switch to it (History drawer "New Chat"). */
    fun createNewConversation() {
        viewModelScope.launch(dispatchers.main) {
            openConversation(null)
        }
    }

    private suspend fun createConversation(): Conversation {
        val conversation = Conversation(title = "New chat")
        conversationRepository.upsertConversation(conversation)
        return conversation
    }

    fun onTextChange(text: String) {
        _uiState.update { it.copy(composerText = text) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val provider = activeProvider ?: return
        val providerAdapter = providerManager.adapterFor(provider)
        val text = state.composerText.trim()
        if (text.isEmpty() || state.isStreaming) return

        streamJob?.cancel()
        streamJob = viewModelScope.launch(dispatchers.main) {
            val conversationId = state.conversationId ?: return@launch

            // Smart routing v1: pick on-device vs cloud before persisting anything, so a
            // failed route leaves the composer text intact for a retry.
            val target = effectiveRoute(state.routingOverride)
            if (state.routingOverride == RoutingOverride.LOCAL && target != RoutingOverride.LOCAL) {
                _uiEvents.emit(ChatUiEvent.ShowNotice(LOCAL_UNAVAILABLE_NOTICE))
            }

            if (target == RoutingOverride.LOCAL) {
                val localProvider = localLlmRuntime.currentProvider()
                if (localProvider == null) {
                    _uiEvents.emit(
                        ChatUiEvent.ShowError(
                            "On-device model failed to load — remove and re-download it in Settings → Providers.",
                        ),
                    )
                    return@launch
                }
                _uiState.update { it.copy(activeRoute = RoutingOverride.LOCAL) }
                val userMessage = Message(
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = text,
                )
                conversationRepository.upsertMessage(userMessage)
                _uiState.update { it.copy(composerText = "", isStreaming = true) }
                // Gemma 4 E2B supports tools, so local routing runs the agent fully offline.
                if (AgentTrigger.shouldUseAgent(text) && localProvider.capabilities.supportsTools) {
                    streamAgentReply(conversationId, localProvider, localProvider.modelId)
                } else {
                    streamAssistantReply(conversationId, localProvider, localProvider.modelId)
                }
                return@launch
            }

            _uiState.update { it.copy(activeRoute = RoutingOverride.CLOUD) }

            // Resolve the model before persisting the user message, so a misconfigured
            // provider aborts cleanly with the composer text still in place for a retry.
            val model = resolveModel(providerAdapter, provider)
            if (model == null) {
                _uiState.update { it.copy(isStreaming = false) }
                _uiEvents.emit(
                    ChatUiEvent.ShowError(
                        "No model available for \"${provider.name}\". Set one under " +
                            "Model in the provider settings — local servers usually need it.",
                    ),
                )
                return@launch
            }

            val userMessage = Message(
                conversationId = conversationId,
                role = MessageRole.USER,
                content = text,
            )
            conversationRepository.upsertMessage(userMessage)
            _uiState.update { it.copy(composerText = "", isStreaming = true) }

            // Agent trigger: a "Jarvis," prefix / action verbs on a tools-capable provider.
            if (AgentTrigger.shouldUseAgent(text) && providerAdapter.capabilities.supportsTools) {
                streamAgentReply(conversationId, providerAdapter, model)
            } else {
                streamAssistantReply(conversationId, providerAdapter, model)
            }
        }
    }

    /**
     * Stop the in-flight stream. The partial assistant response is preserved and marked
     * [MessageStatus.STOPPED].
     */
    fun cancelStreaming() {
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch(dispatchers.main) {
            val streamingMessage = _uiState.value.messages.lastOrNull { it.status == MessageStatus.STREAMING }
            if (streamingMessage != null) {
                conversationRepository.upsertMessage(
                    streamingMessage.copy(status = MessageStatus.STOPPED),
                )
            }
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    isAgentRunning = false,
                    pendingConfirmation = null,
                    agentSteps = emptyList(),
                )
            }
        }
    }

    /**
     * Resolve a parked Sensitive-tier tool call from the UI (Allow/Deny). Denying halts the
     * run and audits the call as cancelled; allowing resumes the ReAct loop.
     */
    fun respondToConfirmation(allow: Boolean) {
        _uiState.update { it.copy(pendingConfirmation = null) }
        pendingGate?.complete(allow)
        pendingGate = null
    }

    private suspend fun streamAgentReply(
        conversationId: String,
        provider: com.jarvis.core.network.LlmProvider,
        model: String,
    ) {
        _uiState.update { it.copy(isAgentRunning = true, agentSteps = emptyList()) }
        val history = conversationRepository.getMessages(conversationId)
        val engine = AgentEngine(
            registry = toolRegistry,
            audit = auditLogger,
            confirmationGate = ConfirmationGate { name, argsJson -> awaitConfirmation(name, argsJson) },
        )
        val request = AgentRunRequest(
            provider = provider,
            modelId = model,
            messages = history,
        )

        // Local step log pushed into state on each event so the Canvas renders live. At most
        // one row is RUNNING at a time: milestones finish as DONE (✓) and the next one becomes
        // the highlighted row.
        val steps = mutableListOf<AgentStep>()

        fun publish() = _uiState.update { it.copy(agentSteps = steps.toList()) }

        /** Rename the running row's text — it stays in flight, keeping its spinner. */
        fun updateRunning(text: String) {
            val index = steps.indexOfLast { it.state == AgentStepState.RUNNING }
            if (index >= 0) {
                steps[index] = steps[index].copy(text = text)
                publish()
            }
        }

        /** Finish the running row: optional new text, then DONE (✓). */
        fun completeRunning(text: String? = null) {
            val index = steps.indexOfLast { it.state == AgentStepState.RUNNING }
            if (index >= 0) {
                steps[index] = steps[index].copy(
                    text = text ?: steps[index].text,
                    state = AgentStepState.DONE,
                )
                publish()
            }
        }

        /** Open a new milestone row (the previous one is already DONE). */
        fun push(text: String) {
            steps += AgentStep(text = text)
            publish()
        }

        // Each canvas row is one milestone: a requested tool call (or a terminal state when the
        // model never needed one). Renames keep the milestone in flight, completions check it off.
        var answerText = ""
        engine.run(request).collect { event ->
            when (event) {
                AgentEvent.RunStarted, is AgentEvent.IterationStarted, is AgentEvent.ToolExecuting -> Unit
                is AgentEvent.ToolRequested -> push("Calling ${event.name}")
                is AgentEvent.ConfirmationRequired -> {
                    // The parked tool stays the highlighted row; the sheet title switches to the prompt.
                    updateRunning("Needs your approval: ${event.name}")
                }
                is AgentEvent.ToolExecuted -> completeRunning(
                    if (event.success) "${event.name} done" else "${event.name} failed",
                )
                is AgentEvent.ToolRejected -> completeRunning("Rejected ${event.name}")
                is AgentEvent.ToolCancelled -> completeRunning("Denied ${event.name}")
                is AgentEvent.FinalAnswer -> {
                    answerText = event.text
                    if (steps.isEmpty()) {
                        // No tools were needed — the run is one visible milestone.
                        push("Answered")
                    }
                    completeRunning()
                }
                is AgentEvent.Failed -> {
                    if (steps.isEmpty()) push("Failed")
                    completeRunning("Failed: ${event.code}")
                    _uiEvents.emit(ChatUiEvent.ShowError("${event.message} (${event.code})"))
                }
                is AgentEvent.StepCapReached -> {
                    if (steps.isEmpty()) push("Stopped")
                    completeRunning()
                    _uiEvents.emit(ChatUiEvent.ShowNotice("Agent hit its step limit after ${event.stepsUsed} steps."))
                }
            }
        }

        if (answerText.isNotBlank()) {
            conversationRepository.upsertMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = answerText,
                    status = MessageStatus.COMPLETE,
                ),
            )
        }
        _uiState.update { it.copy(isStreaming = false, isAgentRunning = false) }
    }

    private suspend fun awaitConfirmation(toolName: String, argsJson: String): Boolean {
        val gate = CompletableDeferred<Boolean>()
        pendingGate = gate
        _uiState.update { it.copy(pendingConfirmation = AgentConfirmation(toolName, argsJson)) }
        return gate.await()
    }

    private suspend fun streamAssistantReply(
        conversationId: String,
        provider: LlmProvider,
        model: String,
    ) {
        val history = conversationRepository.getMessages(conversationId)
        val assistantMessage = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
        )
        conversationRepository.upsertMessage(assistantMessage)

        val request = ChatRequest(
            conversationHistory = history,
            model = model,
        )

        // Streaming-token appends are debounced at 100ms: tokens
        // accumulate in local builders and Room writes are batched, not per-token. The
        // accumulator is authoritative — safer than read-modify-write against UI state,
        // which can drop tokens when deltas arrive faster than the Room Flow re-emits.
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var lastPersistNanos = System.nanoTime()
        var streamError: ChatStreamEvent.Error? = null

        suspend fun persist(status: MessageStatus) {
            conversationRepository.upsertMessage(
                assistantMessage.copy(
                    content = text.toString(),
                    reasoningContent = reasoning.toString().ifBlank { null },
                    status = status,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    errorHint = streamError?.message,
                ),
            )
        }

        try {
            provider.streamChat(request).collect { event ->
                when (event) {
                    is ChatStreamEvent.TokenDelta -> text.append(event.text)
                    is ChatStreamEvent.ReasoningDelta -> reasoning.append(event.text)
                    is ChatStreamEvent.Usage -> {
                        promptTokens = event.promptTokens
                        completionTokens = event.completionTokens
                    }
                    is ChatStreamEvent.Error -> streamError = event
                    is ChatStreamEvent.ToolCallRequested -> Unit
                    ChatStreamEvent.Done -> Unit // completion is handled after the flow ends
                }
                if (text.isNotBlank() && System.nanoTime() - lastPersistNanos >= PERSIST_DEBOUNCE_NS) {
                    lastPersistNanos = System.nanoTime()
                    persist(MessageStatus.STREAMING)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancel mid-stream: partial response kept, marked "stopped".
            if (text.isNotBlank() || reasoning.isNotBlank()) persist(MessageStatus.STOPPED)
            throw e
        } catch (t: Throwable) {
            streamError = ChatStreamEvent.Error(
                code = "stream",
                message = t.message ?: "Stream failed",
                retryable = false,
            )
        }

        val error = streamError
        if (error != null) {
            // Partial text + failure → keep the partial, marked failed.
            if (text.isNotBlank() || reasoning.isNotBlank()) persist(MessageStatus.ERROR)
            _uiState.update { it.copy(isStreaming = false) }
            _uiEvents.emit(ChatUiEvent.ShowError(error.message))
        } else {
            persist(MessageStatus.COMPLETE)
            _uiState.update { it.copy(isStreaming = false) }
        }
    }

    /**
     * Model used for a new request: the provider config's stored model when set, else the
     * first model the server lists (cached per provider id to skip a /v1/models round trip
     * per message). Returns null when nothing resolves — a hard error rather than silently
     * sending an OpenAI-specific guess to a local server, which 400s with "model not found".
     */
    private suspend fun resolveModel(provider: LlmProvider, config: ProviderConfig): String? {
        config.model?.takeIf { it.isNotBlank() }?.let { return it }
        cachedModels?.let { (id, models) ->
            if (id == provider.id && models.isNotEmpty()) return models.first()
        }
        val models = provider.listModels().getOrNull()?.map { it.id }.orEmpty()
        if (models.isNotEmpty()) cachedModels = provider.id to models
        return models.firstOrNull()
    }

    private companion object {
        /** 100ms streaming-persist debounce. */
        const val PERSIST_DEBOUNCE_NS = 100_000_000L

        /** Shown when the user forces Local routing but no on-device model is installed. */
        const val LOCAL_UNAVAILABLE_NOTICE =
            "Local model isn't downloaded yet — using cloud. Install it in Settings → Providers."
    }

    /** Per-provider model-id cache backing [providerModel]. */
    private var cachedModels: Pair<String, List<String>>? = null

    /** Start or stop push-to-talk recording. When stopped, auto-transcribes and sends. */
    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        viewModelScope.launch(dispatchers.main) {
            try {
                audioRecorder.start()
                _uiState.update { it.copy(isRecording = true) }
            } catch (e: Exception) {
                _uiEvents.emit(ChatUiEvent.ShowError("Microphone permission required"))
            }
        }
    }

    private fun stopRecordingAndTranscribe() {
        viewModelScope.launch(dispatchers.main) {
            _uiState.update { it.copy(isRecording = false, isTranscribing = true) }
            val audioData = with(kotlinx.coroutines.Dispatchers.IO) {
                audioRecorder.stop()
            } ?: run {
                _uiState.update { it.copy(isTranscribing = false) }
                return@launch
            }

            if (audioData.isEmpty()) {
                _uiState.update { it.copy(isTranscribing = false) }
                return@launch
            }

            sttProvider.transcribe(audioData, AudioFormat.WAV)
                .onSuccess { result ->
                    _uiState.update { it.copy(composerText = result.text, isTranscribing = false) }
                    if (result.text.isNotBlank()) {
                        sendMessage()
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isTranscribing = false) }
                    _uiEvents.emit(ChatUiEvent.ShowError("Transcription failed: ${e.message}"))
                }
        }
    }

    /** Play TTS for the most recent assistant response. */
    fun speakLastResponse() {
        val lastAssistant = _uiState.value.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotEmpty() }
            ?: return

        viewModelScope.launch(dispatchers.main) {
            _uiState.update { it.copy(isPlayingAudio = true) }
            try {
                ttsProvider.synthesize(lastAssistant.content, TtsVoice.NOVA)
                    .onSuccess { result ->
                        audioPlayer.play(result.audioData, result.format.extension)
                    }
                    .onFailure { e ->
                        _uiEvents.emit(ChatUiEvent.ShowError("TTS failed: ${e.message}"))
                    }
            } finally {
                _uiState.update { it.copy(isPlayingAudio = false) }
            }
        }
    }

    fun stopSpeaking() {
        audioPlayer.stop()
        _uiState.update { it.copy(isPlayingAudio = false) }
    }
}
