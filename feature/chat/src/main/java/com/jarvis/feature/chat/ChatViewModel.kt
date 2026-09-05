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
import com.jarvis.core.common.DEFAULT_CONVERSATION_TITLE
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.database.repository.ConversationRepository
import com.jarvis.core.ml.LocalConnectivity
import com.jarvis.core.ml.LocalLlmRuntime
import com.jarvis.core.ml.LocalModelState
import com.jarvis.core.ml.LocalModelStore
import com.jarvis.core.navigation.Routes
import com.jarvis.core.preferences.UserPreferencesRepository
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderManager
import com.jarvis.core.voice.AudioFormat
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.LiveSttSession
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.TtsVoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.Locale

data class ChatUiState(
    val conversationId: String? = null,
    val conversationTitle: String = DEFAULT_CONVERSATION_TITLE,
    val messages: List<Message> = emptyList(),
    val composerText: String = "",
    /** Initial conversation load — suppresses the empty-state flash while reading Room. */
    val isLoadingConversation: Boolean = true,
    val isStreaming: Boolean = false,
    /** Turn preparation (model resolution / engine load) before the stream starts. */
    val isPreparingSend: Boolean = false,
    val isSendingEnabled: Boolean = true,
    /** Per-chat routing override (Auto / Local / Cloud). Persists with the conversation. */
    val routingOverride: RoutingOverride = RoutingOverride.AUTO,
    /** Effective route for the current response, shown on the route badge. */
    val activeRoute: RoutingOverride = RoutingOverride.CLOUD,
    /**
     * What actually answered the last turn — route plus display label ("On-device",
     * "gpt-4o-mini • OpenAI"), rendered as the badge under the latest assistant message.
     * Null hides the badge.
     */
    val routeBadge: RouteBadge? = null,
    /** Voice recording state. */
    val isRecording: Boolean = false,
    /** Transcribing audio to text. */
    val isTranscribing: Boolean = false,
    /** TTS audio playback state. The id of the message currently being spoken, or null.
     *  Per-message id (not a boolean) so the "stop" icon only appears on the row the user tapped. */
    val playingAudioMessageId: String? = null,
    /** A ReAct agent run is in progress. */
    val isAgentRunning: Boolean = false,
    /** Sensitive-tier tool awaiting an explicit user decision. */
    val pendingConfirmation: AgentConfirmation? = null,
    /** Live step log rendered as the transcript's in-flight tail during an agent run. */
    val agentSteps: List<AgentStep> = emptyList(),
    /** Reasoning-effort setting; the composer pill cycles OFF → AUTO → ON. */
    val thinkMode: ThinkMode = ThinkMode.AUTO,
)

sealed interface ChatUiEvent {
    data class ShowError(
        val message: String,
    ) : ChatUiEvent

    data class ShowNotice(
        val message: String,
    ) : ChatUiEvent
}

/**
 * What answered the last turn, for the route badge on the chat canvas: the route that ran
 * plus a display label ("On-device", "gpt-4o-mini • OpenAI").
 */
data class RouteBadge(
    val route: RoutingOverride,
    val label: String,
)

/** A Sensitive-tier tool call parked until the user taps Allow/Deny. */
data class AgentConfirmation(
    val toolName: String,
    val argsJson: String,
)

enum class AgentStepState { RUNNING, DONE, FAILED }

/** One row in the agent step list: bold title, optional observation detail. */
data class AgentStep(
    val text: String,
    val state: AgentStepState = AgentStepState.RUNNING,
    /** Secondary observation line under the title (e.g. a tool's result summary). Null hides the line. */
    val detail: String? = null,
    /** Measured wall-clock for a finished step ("1.4s"). Null falls back to the state label. */
    val durationLabel: String? = null,
    /** 0..1 fraction for the running row's progress bar. Null renders indeterminate. */
    val progress: Float? = null,
)

/**
 * MVI ViewModel for the chat screen: one immutable UiState, events flow up, streaming
 * responses update the assistant message as tokens arrive.
 */
@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
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
        private val userPreferences: UserPreferencesRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ChatUiState())
        val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

        /**
         * One-shot UI events. Buffered + tryEmit: a fire-and-forget emission never suspends
         * when nobody collects, and bursts (stream error + routing notice) each get shown.
         */
        private val _uiEvents = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 16)
        val uiEvents: SharedFlow<ChatUiEvent> = _uiEvents.asSharedFlow()

        private var activeProvider: ProviderConfig? = null

        /** Routing decision reason for the turn in flight — persisted via Message.routeUsed. */
        private var lastRouteReason: String? = null

        /** Agent step cap from user preferences; null until the first read completes. */
        private var agentStepCap: Int? = null

        /** Reasoning-effort mode observed from preferences (defaults to AUTO until first read). */
        private var thinkMode: ThinkMode = ThinkMode.AUTO

        /** Cautious mode: when true, every agent tool call requires user confirmation. */
        private var cautiousMode = false

        /** Handle to the in-flight streaming request, used by [cancelStreaming]. */
        private var streamJob: Job? = null

        /** Active conversation observer — cancelled when switching conversations. */
        private var messagesJob: Job? = null

        /** Live mic recognition session, when the STT provider supports one. */
        private var liveSttSession: LiveSttSession? = null

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
                    refreshSendEnabled()
                }
            }
            // A LOCAL-only setup must be sendable too: no cloud provider is required when an
            // on-device model is installed and the route resolves to LOCAL.
            viewModelScope.launch(dispatchers.main) {
                localModelStore.status.collect { _ -> refreshSendEnabled() }
            }

            // Observe the agent step cap so a Settings change applies to the next agent run
            // without recreating the ViewModel.
            viewModelScope.launch(dispatchers.main) {
                userPreferences.agentStepCap.collect { cap -> agentStepCap = cap }
            }
            // Same for the reasoning-effort mode (PR-6's Settings radio writes the same key).
            viewModelScope.launch(dispatchers.main) {
                userPreferences.thinkMode.collect { mode ->
                    thinkMode = mode
                    _uiState.update { it.copy(thinkMode = mode) }
                }
            }
            // Cautious mode gates every agent tool call (passed to AgentEngine.forceConfirm).
            viewModelScope.launch(dispatchers.main) {
                userPreferences.cautiousModeEnabled.collect { enabled -> cautiousMode = enabled }
            }

            // Deleting the active conversation (History drawer) must not strand the chat: when
            // the current conversation disappears, switch to a fresh one. The same stream keeps
            // the header title current when it changes out-of-band (drawer rename, auto-title).
            viewModelScope.launch(dispatchers.main) {
                conversationRepository.observeConversations().collect { conversations ->
                    val currentId = _uiState.value.conversationId
                    val current = conversations.firstOrNull { it.id == currentId }
                    when {
                        currentId != null && current == null -> openConversation(null)
                        current != null && current.title != _uiState.value.conversationTitle ->
                            _uiState.update { it.copy(conversationTitle = current.title) }
                    }
                }
            }
        }

        private fun refreshSendEnabled() {
            _uiState.update {
                it.copy(isSendingEnabled = activeProvider != null || localModelReady)
            }
        }

        private suspend fun openConversation(conversationId: String?, preserveRouting: RoutingOverride? = null) {
            val conversation =
                conversationId?.let { conversationRepository.getConversation(it) }
                    ?: createConversation(preserveRouting ?: RoutingOverride.AUTO)

            _uiState.update {
                it.copy(
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    routingOverride = conversation.routingOverride,
                    // No message text to classify yet — the decision tree's text heuristics
                    // (privacy / realtime / heavy) simply don't fire on an empty string.
                    activeRoute =
                        RoutingClassifier
                            .classify("", conversation.routingOverride, localModelReady, isOnline())
                            .route,
                    // No turn has been answered in this conversation yet — hide any badge
                    // carried over from the previous one.
                    routeBadge = null,
                    isLoadingConversation = false,
                )
            }
            // Exactly one observer per ViewModel: cancel the previous conversation's collector
            // before opening the new one, otherwise old-conversation writes bleed into messages.
            messagesJob?.cancel()
            messagesJob =
                viewModelScope.launch(dispatchers.main) {
                    conversationRepository.observeMessages(conversation.id).collectLatest { messages ->
                        _uiState.update { state -> state.copy(messages = messages) }
                    }
                }
            messagesJob?.join()

            // Reopening restores the badge for the last answered turn from its persisted
            // routing reason alone — the live session's richer "model • provider" label isn't
            // reconstructible without pinning provider/model ids, which stays out of scope.
            conversationRepository
                .getMessages(conversation.id)
                .lastOrNull { it.routeUsed != null }
                ?.routeUsed
                ?.let { reason ->
                    badgeFromPersistedReason(reason)?.let { badge ->
                        _uiState.update { it.copy(routeBadge = badge) }
                    }
                }
        }

        /** Rebuild route badge from persisted reason — model/provider not stored, so label is generic. */
        private fun badgeFromPersistedReason(reasonName: String): RouteBadge? =
            when (runCatching { RoutingReason.valueOf(reasonName) }.getOrNull()) {
                RoutingReason.FORCED_LOCAL,
                RoutingReason.PRIVACY_LOCAL,
                RoutingReason.LIGHT_LOCAL,
                -> RouteBadge(RoutingOverride.LOCAL, "On-device")

                RoutingReason.FORCED_CLOUD,
                RoutingReason.REALTIME_CLOUD,
                RoutingReason.HEAVY_GENERATIVE_CLOUD,
                RoutingReason.DEFAULT_CLOUD,
                RoutingReason.FORCED_LOCAL_FALLBACK,
                -> RouteBadge(RoutingOverride.CLOUD, "Cloud")

                null -> null
            }

        /** Open a conversation selected from the History drawer. */
        fun openConversationById(conversationId: String) {
            if (_uiState.value.conversationId == conversationId) return
            viewModelScope.launch(dispatchers.main) {
                _uiState.update { it.copy(isLoadingConversation = true, messages = emptyList()) }
                openConversation(conversationId)
            }
        }

        /** Persist routing override with the conversation. */
        fun setRoutingOverride(override: RoutingOverride) {
            val conversationId = _uiState.value.conversationId ?: return
            viewModelScope.launch(dispatchers.main) {
                conversationRepository.getConversation(conversationId)?.let { current ->
                    conversationRepository.upsertConversation(current.copy(routingOverride = override))
                }
                _uiState.update {
                    it.copy(
                        routingOverride = override,
                        activeRoute = RoutingClassifier.classify("", override, localModelReady, isOnline()).route,
                    )
                }
                if (override == RoutingOverride.LOCAL && !localModelReady) {
                    _uiEvents.tryEmit(ChatUiEvent.ShowNotice(LOCAL_UNAVAILABLE_NOTICE))
                }
            }
        }

        /** True when the network is reachable; a resolver failure counts as offline. */
        private fun isOnline(): Boolean = runCatching { connectivity.isOnline() }.getOrDefault(false)

        private fun classifyRoute(
            override: RoutingOverride,
            message: String,
        ): RoutingDecision = RoutingClassifier.classify(message, override, localModelReady, isOnline())

        /** Persist reasoning-effort mode. */
        fun setThinkMode(mode: ThinkMode) {
            viewModelScope.launch(dispatchers.main) {
                userPreferences.setThinkMode(mode)
            }
        }

        /** On-device model installed and ready. */
        private val localModelReady: Boolean
            get() = runCatching { localModelStore.status.value is LocalModelState.Ready }.getOrDefault(false)

        /** Create a fresh conversation, preserving the current routing override. */
        fun createNewConversation() {
            if (_uiState.value.isStreaming) return
            viewModelScope.launch(dispatchers.main) {
                _uiState.update { it.copy(isLoadingConversation = true, messages = emptyList()) }
                val currentOverride = _uiState.value.routingOverride
                openConversation(null, preserveRouting = currentOverride)
            }
        }

        private suspend fun createConversation(routingOverride: RoutingOverride = RoutingOverride.AUTO): Conversation {
            val conversation = Conversation(title = DEFAULT_CONVERSATION_TITLE, routingOverride = routingOverride)
            conversationRepository.upsertConversation(conversation)
            return conversation
        }

        /** Auto-name from the first user message. */
        private suspend fun autoTitleConversation(conversationId: String, firstMessageText: String) {
            val conversation = conversationRepository.getConversation(conversationId) ?: return
            if (conversation.title != DEFAULT_CONVERSATION_TITLE) return
            val title =
                firstMessageText
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(TITLE_MAX_CHARS)
            if (title.isEmpty()) return
            conversationRepository.renameConversation(conversationId, title)
            // Update the header directly; the conversations observer syncs it a beat later anyway.
            _uiState.update { it.copy(conversationTitle = title) }
        }

        fun onTextChange(text: String) {
            _uiState.update { it.copy(composerText = text) }
        }

        fun sendMessage() {
            val state = _uiState.value
            val text = state.composerText.trim()
            if (text.isEmpty() || state.isStreaming || state.isPreparingSend || state.isLoadingConversation) return

            streamJob?.cancel()
            streamJob =
                viewModelScope.launch(dispatchers.main) {
                    val conversationId = state.conversationId ?: return@launch

                    // Pick route before persisting so a failed route leaves composer text for retry.
                    val decision = classifyRoute(state.routingOverride, text)
                    val target = decision.route
                    if (decision.reason == RoutingReason.FORCED_LOCAL_FALLBACK) {
                        _uiEvents.tryEmit(ChatUiEvent.ShowNotice(LOCAL_UNAVAILABLE_NOTICE))
                    }
                    // The reason rides on the user message's routeUsed column — the assistant
                    // reply copies it in persist(), giving every turn an auditable decision.
                    lastRouteReason = decision.reason.name

                    if (target == RoutingOverride.LOCAL) {
                        // Engine load takes seconds — show a preparing state and block double-taps.
                        _uiState.update { it.copy(isPreparingSend = true) }
                        val localProvider =
                            try {
                                localLlmRuntime.currentProvider()
                            } finally {
                                _uiState.update { it.copy(isPreparingSend = false) }
                            }
                        if (localProvider == null) {
                            _uiEvents.tryEmit(
                                ChatUiEvent.ShowError(
                                    localLlmRuntime.lastFailure
                                        ?: "On-device model failed to load. Remove and re-download " +
                                        "it in Settings → Providers.",
                                ),
                            )
                            return@launch
                        }
                        _uiState.update {
                            it.copy(
                                activeRoute = RoutingOverride.LOCAL,
                                routeBadge = RouteBadge(RoutingOverride.LOCAL, "On-device"),
                            )
                        }
                        val userMessage =
                            Message(
                                conversationId = conversationId,
                                role = MessageRole.USER,
                                content = text,
                                routeUsed = lastRouteReason,
                            )
                        conversationRepository.upsertMessage(userMessage)
                        autoTitleConversation(conversationId, text)
                        _uiState.update { it.copy(composerText = "", isStreaming = true) }
                        // Local model supports tools, so agent runs fully offline.
                        if (AgentTrigger.shouldUseAgent(text) && localProvider.capabilities.supportsTools) {
                            streamAgentReply(
                                conversationId,
                                localProvider,
                                localProvider.modelId,
                                reasoningRequested = ThinkModeHeuristic.shouldThink(text, thinkMode),
                            )
                        } else {
                            streamAssistantReply(
                                conversationId,
                                localProvider,
                                localProvider.modelId,
                                reasoningRequested = ThinkModeHeuristic.shouldThink(text, thinkMode),
                            )
                        }
                        return@launch
                    }

                    // Cloud path requires a configured provider.
                    val provider = activeProvider
                    if (provider == null) {
                        _uiEvents.tryEmit(
                            ChatUiEvent.ShowNotice(
                                "No cloud provider configured. Add one in Settings, or switch routing to Local.",
                            ),
                        )
                        return@launch
                    }
                    val providerAdapter = providerManager.adapterFor(provider)

                    _uiState.update { it.copy(activeRoute = RoutingOverride.CLOUD) }

                    // Resolve model before persisting so a misconfigured provider aborts cleanly.
                    _uiState.update { it.copy(isPreparingSend = true) }
                    val model =
                        try {
                            resolveModel(providerAdapter, provider)
                        } finally {
                            _uiState.update { it.copy(isPreparingSend = false) }
                        }
                    if (model == null) {
                        _uiEvents.tryEmit(
                            ChatUiEvent.ShowError(
                                "No model available for \"${provider.name}\". Set a model in the provider " +
                                    "settings; local servers usually need one.",
                            ),
                        )
                        return@launch
                    }

                    _uiState.update { it.copy(routeBadge = RouteBadge(RoutingOverride.CLOUD, "$model • ${provider.name}")) }

                    val userMessage =
                        Message(
                            conversationId = conversationId,
                            role = MessageRole.USER,
                            content = text,
                            routeUsed = lastRouteReason,
                        )
                    conversationRepository.upsertMessage(userMessage)
                    autoTitleConversation(conversationId, text)
                    _uiState.update { it.copy(composerText = "", isStreaming = true) }

                    // Agent trigger: a "Jarvis," prefix / action verbs on a tools-capable provider.
                    if (AgentTrigger.shouldUseAgent(text) && providerAdapter.capabilities.supportsTools) {
                        streamAgentReply(
                            conversationId,
                            providerAdapter,
                            model,
                            reasoningRequested = ThinkModeHeuristic.shouldThink(text, thinkMode),
                        )
                    } else {
                        streamAssistantReply(
                            conversationId,
                            providerAdapter,
                            model,
                            reasoningRequested = ThinkModeHeuristic.shouldThink(text, thinkMode),
                        )
                    }
                }
        }

        /** Cancel streaming — partial response preserved. */
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

        /** Regenerate the last response. */
        fun regenerate() {
            val state = _uiState.value
            val conversationId = state.conversationId ?: return
            if (state.isStreaming || state.isPreparingSend || state.isLoadingConversation) return

            val lastUserIndex = state.messages.indexOfLast { it.role == MessageRole.USER }
            if (lastUserIndex < 0) return

            streamJob?.cancel()
            streamJob =
                viewModelScope.launch(dispatchers.main) {
                    // Remove everything after the last user turn so the model sees a clean prompt.
                    val lastUser = state.messages[lastUserIndex]
                    val toRemove = state.messages.drop(lastUserIndex + 1)
                    toRemove.forEach { conversationRepository.deleteMessage(it.id) }

                    val decision = classifyRoute(state.routingOverride, lastUser.content)
                    val target = decision.route
                    if (decision.reason == RoutingReason.FORCED_LOCAL_FALLBACK) {
                        _uiEvents.tryEmit(ChatUiEvent.ShowNotice(LOCAL_UNAVAILABLE_NOTICE))
                    }

                    if (target == RoutingOverride.LOCAL) {
                        _uiState.update { it.copy(isPreparingSend = true) }
                        val localProvider =
                            try {
                                localLlmRuntime.currentProvider()
                            } finally {
                                _uiState.update { it.copy(isPreparingSend = false) }
                            }
                        if (localProvider == null) {
                            _uiEvents.tryEmit(
                                ChatUiEvent.ShowError(
                                    localLlmRuntime.lastFailure
                                        ?: "On-device model failed to load. Remove and re-download " +
                                        "it in Settings → Providers.",
                                ),
                            )
                            return@launch
                        }
                        _uiState.update {
                            it.copy(
                                activeRoute = RoutingOverride.LOCAL,
                                routeBadge = RouteBadge(RoutingOverride.LOCAL, "On-device"),
                                isStreaming = true,
                            )
                        }
                        if (AgentTrigger.shouldUseAgent(lastUser.content) && localProvider.capabilities.supportsTools) {
                            streamAgentReply(
                                conversationId,
                                localProvider,
                                localProvider.modelId,
                                reasoningRequested = ThinkModeHeuristic.shouldThink(lastUser.content, thinkMode),
                            )
                        } else {
                            streamAssistantReply(
                                conversationId,
                                localProvider,
                                localProvider.modelId,
                                reasoningRequested = ThinkModeHeuristic.shouldThink(lastUser.content, thinkMode),
                            )
                        }
                        return@launch
                    }

                    val provider = activeProvider
                    if (provider == null) {
                        _uiEvents.tryEmit(
                            ChatUiEvent.ShowNotice(
                                "No cloud provider configured. Add one in Settings, or switch routing to Local.",
                            ),
                        )
                        return@launch
                    }
                    val providerAdapter = providerManager.adapterFor(provider)

                    _uiState.update { it.copy(activeRoute = RoutingOverride.CLOUD) }

                    _uiState.update { it.copy(isPreparingSend = true) }
                    val model =
                        try {
                            resolveModel(providerAdapter, provider)
                        } finally {
                            _uiState.update { it.copy(isPreparingSend = false) }
                        }
                    if (model == null) {
                        _uiEvents.tryEmit(
                            ChatUiEvent.ShowError(
                                "No model available for \"${provider.name}\". Set a model in the provider " +
                                    "settings; local servers usually need one.",
                            ),
                        )
                        return@launch
                    }

                    _uiState.update { it.copy(routeBadge = RouteBadge(RoutingOverride.CLOUD, "$model • ${provider.name}")) }

                    _uiState.update { it.copy(isStreaming = true) }
                    if (AgentTrigger.shouldUseAgent(lastUser.content) && providerAdapter.capabilities.supportsTools) {
                        streamAgentReply(
                            conversationId,
                            providerAdapter,
                            model,
                            reasoningRequested = ThinkModeHeuristic.shouldThink(lastUser.content, thinkMode),
                        )
                    } else {
                        streamAssistantReply(
                            conversationId,
                            providerAdapter,
                            model,
                            reasoningRequested = ThinkModeHeuristic.shouldThink(lastUser.content, thinkMode),
                        )
                    }
                }
        }

        /** Resolve a parked tool call from the UI. */
        fun respondToConfirmation(allow: Boolean) {            _uiState.update { it.copy(pendingConfirmation = null) }
            pendingGate?.complete(allow)
            pendingGate = null
        }

        private suspend fun streamAgentReply(
            conversationId: String,
            provider: com.jarvis.core.network.LlmProvider,
            model: String,
            reasoningRequested: Boolean = false,
        ) {
            _uiState.update { it.copy(isAgentRunning = true, agentSteps = emptyList()) }
            val history = conversationRepository.getMessages(conversationId)
            val engine =
                AgentEngine(
                    registry = toolRegistry,
                    audit = auditLogger,
                    confirmationGate = ConfirmationGate { name, argsJson -> awaitConfirmation(name, argsJson) },
                    // Preferences-driven cap; the engine default applies until the first read lands.
                    stepCap = agentStepCap ?: AgentEngine.DEFAULT_STEP_CAP,
                    forceConfirm = cautiousMode,
                )
            val request =
                AgentRunRequest(
                    provider = provider,
                    modelId = model,
                    messages = history,
                    reasoningRequested = reasoningRequested,
                )

            // Live step log for the in-flight tail. At most one row is RUNNING at a time.
            val steps = mutableListOf<AgentStep>()
            var runningSinceMs = 0L

            fun publish() = _uiState.update { it.copy(agentSteps = steps.toList()) }

            fun updateRunning(text: String) {
                val index = steps.indexOfLast { it.state == AgentStepState.RUNNING }
                if (index >= 0) {
                    steps[index] = steps[index].copy(text = text)
                    publish()
                }
            }

            /** Finish the running row and persist the milestone. */
            suspend fun completeRunning(
                state: AgentStepState,
                text: String? = null,
                detail: String? = null,
            ) {
                val index = steps.indexOfLast { it.state == AgentStepState.RUNNING }
                if (index >= 0) {
                    val finished = steps[index]
                    steps[index] =
                        finished.copy(
                            text = text ?: finished.text,
                            state = state,
                            detail = detail ?: finished.detail,
                            durationLabel = formatAgentDuration(System.currentTimeMillis() - runningSinceMs),
                        )
                    val summary =
                        buildString {
                            append(steps[index].text)
                            if (detail != null) append(" — ${detail.take(OBSERVATION_PREVIEW_CHARS)}")
                        }
                    persistMilestone(conversationId, summary, failed = state == AgentStepState.FAILED)
                    publish()
                }
            }

            fun push(text: String) {
                runningSinceMs = System.currentTimeMillis()
                steps += AgentStep(text = text)
                publish()
            }

            var answerText = ""
            try {
                engine.run(request).collect { event ->
                    when (event) {
                        AgentEvent.RunStarted, is AgentEvent.IterationStarted, is AgentEvent.ToolExecuting -> Unit
                        is AgentEvent.ToolRequested -> push("Calling ${event.name}")
                        is AgentEvent.ConfirmationRequired ->
                            updateRunning("Needs your approval: ${event.name}")
                        is AgentEvent.ToolExecuted ->
                            completeRunning(
                                if (event.success) AgentStepState.DONE else AgentStepState.FAILED,
                                if (event.success) "${event.name} done" else "${event.name} failed",
                                event.observationText.take(OBSERVATION_PREVIEW_CHARS).ifBlank { null },
                            )
                        is AgentEvent.ToolRejected ->
                            completeRunning(
                                AgentStepState.FAILED,
                                "Rejected ${event.name}",
                                event.reason.take(OBSERVATION_PREVIEW_CHARS).ifBlank { null },
                            )
                        is AgentEvent.ToolCancelled -> completeRunning(AgentStepState.DONE, "Denied ${event.name}")
                        is AgentEvent.FinalAnswer -> {
                            answerText = event.text
                            completeRunning(AgentStepState.DONE)
                        }
                        is AgentEvent.Failed -> {
                            completeRunning(
                                AgentStepState.FAILED,
                                "Failed: ${event.code}",
                                event.message.take(OBSERVATION_PREVIEW_CHARS).ifBlank { null },
                            )
                            _uiEvents.tryEmit(ChatUiEvent.ShowError("${event.message} (${event.code})"))
                        }
                        is AgentEvent.StepCapReached -> {
                            completeRunning(AgentStepState.DONE)
                            _uiEvents.tryEmit(
                                ChatUiEvent.ShowNotice("Agent hit its step limit after ${event.stepsUsed} steps."),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }

            if (answerText.isNotBlank()) {
                conversationRepository.upsertMessage(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = answerText,
                        status = MessageStatus.COMPLETE,
                        routeUsed = lastRouteReason,
                    ),
                )
            }
            _uiState.update { it.copy(isStreaming = false, isAgentRunning = false) }
        }

        private suspend fun awaitConfirmation(
            toolName: String,
            argsJson: String,
        ): Boolean {
            val gate = CompletableDeferred<Boolean>()
            pendingGate = gate
            _uiState.update { it.copy(pendingConfirmation = AgentConfirmation(toolName, argsJson)) }
            return gate.await()
        }

        private fun formatAgentDuration(elapsedMs: Long): String =
            String.format(Locale.US, "%.1fs", elapsedMs.coerceAtLeast(0) / 1000.0)

        /** Persist a finished milestone as a display-only TOOL row. */
        private suspend fun persistMilestone(
            conversationId: String,
            text: String,
            failed: Boolean = false,
        ) {
            conversationRepository.upsertMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.TOOL,
                    content = text,
                    status = if (failed) MessageStatus.ERROR else MessageStatus.COMPLETE,
                ),
            )
        }

        private suspend fun streamAssistantReply(
            conversationId: String,
            provider: LlmProvider,
            model: String,
            reasoningRequested: Boolean = false,
        ) {
            val history = conversationRepository.getMessages(conversationId)
            val assistantMessage =
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    status = MessageStatus.STREAMING,
                    routeUsed = lastRouteReason,
                )
            conversationRepository.upsertMessage(assistantMessage)

            val request =
                ChatRequest(
                    conversationHistory = history,
                    model = model,
                    thinkMode = thinkMode,
                    reasoningRequested = reasoningRequested,
                )

            // Debounce Room writes at 100ms — the accumulator is authoritative.
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
            } catch (e: CancellationException) {
                if (text.isNotBlank() || reasoning.isNotBlank()) persist(MessageStatus.STOPPED)
                throw e
            } catch (t: Throwable) {
                streamError =
                    ChatStreamEvent.Error(
                        code = "stream",
                        message = t.message ?: "Stream failed",
                        retryable = false,
                    )
            }

            val error = streamError
            if (error != null) {
                if (text.isNotBlank() || reasoning.isNotBlank()) persist(MessageStatus.ERROR)
                _uiState.update { it.copy(isStreaming = false) }
                _uiEvents.tryEmit(ChatUiEvent.ShowError(error.message))
            } else {
                persist(MessageStatus.COMPLETE)
                _uiState.update { it.copy(isStreaming = false) }
            }
        }

        /** Resolve model: stored config model, or first from server (cached). */
        private suspend fun resolveModel(
            provider: LlmProvider,
            config: ProviderConfig,
        ): String? {
            config.model?.takeIf { it.isNotBlank() }?.let { return it }
            cachedModels?.let { (id, models) ->
                if (id == provider.id && models.isNotEmpty()) return models.first()
            }
            val models =
                provider
                    .listModels()
                    .getOrNull()
                    ?.map { it.id }
                    .orEmpty()
            if (models.isNotEmpty()) cachedModels = provider.id to models
            return models.firstOrNull()
        }

        /** Per-provider model-id cache backing [providerModel]. */
        private var cachedModels: Pair<String, List<String>>? = null

        fun toggleRecording() {
            if (_uiState.value.isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        private fun startRecording() {
            viewModelScope.launch(dispatchers.main) {
                val liveSession = runCatching { sttProvider.startLiveSession() }.getOrNull()
                if (liveSession != null) {
                    startLiveRecording(liveSession)
                    return@launch
                }

                try {
                    audioRecorder.start()
                    _uiState.update { it.copy(isRecording = true) }
                } catch (e: SecurityException) {
                    _uiEvents.tryEmit(ChatUiEvent.ShowError("Microphone permission required"))
                } catch (e: IllegalStateException) {
                    _uiEvents.tryEmit(ChatUiEvent.ShowError(e.message ?: "Could not start recording"))
                }
            }
        }

        private fun startLiveRecording(session: LiveSttSession) {
            liveSttSession = session
            _uiState.update { it.copy(isRecording = true) }
            session.startListening(
                onPartial = { partial ->
                    if (partial.isNotBlank()) {
                        _uiState.update { it.copy(composerText = partial) }
                    }
                },
                onResult = { finalText ->
                    if (_uiState.value.isRecording) stopLiveSession()
                    _uiState.update { it.copy(isTranscribing = false) }
                    if (finalText.isNotBlank()) {
                        _uiState.update { it.copy(composerText = finalText) }
                        sendMessage()
                    }
                },
                onError = { message ->
                    if (_uiState.value.isRecording) stopLiveSession()
                    _uiState.update { it.copy(isRecording = false, isTranscribing = false) }
                    _uiEvents.tryEmit(ChatUiEvent.ShowError(message))
                },
            )
        }

        private fun stopLiveSession() {
            liveSttSession?.let { session ->
                session.stopListening()
                viewModelScope.launch(dispatchers.main) { session.close() }
            }
            liveSttSession = null
        }

        private fun stopRecording() {
            if (liveSttSession != null) {
                _uiState.update { it.copy(isRecording = false, isTranscribing = true) }
                liveSttSession?.stopListening()
                viewModelScope.launch(dispatchers.main) {
                    liveSttSession?.close()
                    liveSttSession = null
                    delay(LIVE_RESULT_TIMEOUT_MS)
                    if (_uiState.value.isTranscribing) {
                        _uiState.update { it.copy(isTranscribing = false) }
                    }
                }
                return
            }

            viewModelScope.launch(dispatchers.main) {
                _uiState.update { it.copy(isRecording = false, isTranscribing = true) }
                val audioData =
                    with(dispatchers.io) {
                        runCatching { audioRecorder.stop() }.getOrNull()
                    } ?: run {
                        _uiState.update { it.copy(isTranscribing = false) }
                        _uiEvents.tryEmit(ChatUiEvent.ShowNotice("Nothing was recorded"))
                        return@launch
                    }

                sttProvider
                    .transcribe(audioData, AudioFormat.WAV)
                    .onSuccess { result ->
                        _uiState.update { it.copy(composerText = result.text, isTranscribing = false) }
                        if (result.text.isNotBlank()) {
                            sendMessage()
                        }
                    }.onFailure { e ->
                        _uiState.update { it.copy(isTranscribing = false) }
                        _uiEvents.tryEmit(ChatUiEvent.ShowError("Transcription failed: ${e.message}"))
                    }
            }
        }

        /** Release mic resources without sending. */
        fun stopLiveSessionAndRecorder() {
            stopLiveSession()
            if (_uiState.value.isRecording) {
                _uiState.update { it.copy(isRecording = false, isTranscribing = false) }
            }
            audioRecorder.cancel()
        }

        /** Play TTS for the most recent assistant response. */
        fun speakLastResponse() {
            val lastAssistant =
                _uiState.value.messages
                    .lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotEmpty() }
                    ?: return
            speakMessage(lastAssistant.id, lastAssistant.content)
        }

        /**
         * Play TTS for a specific message. The id is the row key, so the "stop" icon only
         * appears on the row the user tapped. A second tap on the same row stops playback
         * and clears the marker; tapping a different row interrupts and switches to it.
         */
        fun speakMessage(messageId: String, content: String) {
            // Same row tapped again while speaking — treat as stop.
            if (_uiState.value.playingAudioMessageId == messageId) {
                stopSpeaking()
                return
            }
            // Switching rows: stop any in-flight playback before starting a new one.
            audioPlayer.stop()

            viewModelScope.launch(dispatchers.main) {
                _uiState.update { it.copy(playingAudioMessageId = messageId) }
                try {
                    ttsProvider
                        .synthesize(content, TtsVoice.NOVA)
                        .onSuccess { result ->
                            audioPlayer.play(result.audioData, result.format.extension)
                        }.onFailure { e ->
                            _uiEvents.tryEmit(ChatUiEvent.ShowError("TTS failed: ${e.message}"))
                        }
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    // Only clear the marker if this row is still the one playing — a new
                    // tap on a different row may have taken over while we were still
                    // synthesizing for the previous id.
                    if (_uiState.value.playingAudioMessageId == messageId) {
                        _uiState.update { it.copy(playingAudioMessageId = null) }
                    }
                }
            }
        }

        fun stopSpeaking() {
            audioPlayer.stop()
            _uiState.update { it.copy(playingAudioMessageId = null) }
        }

        override fun onCleared() {
            stopLiveSession()
            audioRecorder.cancel()
            audioPlayer.stop()
            super.onCleared()
        }

        private companion object {
            /** 100ms streaming-persist debounce. */
            const val PERSIST_DEBOUNCE_NS = 100_000_000L

            /** Grace period for a live recognizer to deliver its final result after stop. */
            const val LIVE_RESULT_TIMEOUT_MS = 500L

            /** Shown when the user forces Local routing but no on-device model is installed. */
            const val LOCAL_UNAVAILABLE_NOTICE =
                "The local model is not downloaded yet, so cloud was used instead. " +
                    "Install it in Settings → Providers."

            /** Canvas detail lines carry an observation preview, never the full raw output. */
            const val OBSERVATION_PREVIEW_CHARS = 140

            /** Auto-derived conversation titles cap here; longer openers are truncated. */
            const val TITLE_MAX_CHARS = 50
        }
    }
