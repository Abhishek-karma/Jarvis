package com.jarvis.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AgentTrigger
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.ContextManager
import com.jarvis.core.agent.DefaultToolPolicy
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.tools.WebTools
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
import com.jarvis.core.preferences.ChatMode
import com.jarvis.core.preferences.UserPreferencesRepository
import com.jarvis.core.network.CategorizedProviderError
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ProviderManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.Locale

@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
        private val providerManager: ProviderManager,
        private val dispatchers: DispatcherProvider,
        private val voiceManager: ChatVoiceManager,
        private val toolRegistry: ToolRegistry,
        private val auditLogger: AuditLogger,
        private val localModelStore: LocalModelStore,
        private val localLlmRuntime: LocalLlmRuntime,
        private val connectivity: LocalConnectivity,
        private val userPreferences: UserPreferencesRepository,
        private val conversationContextManager: ConversationContextManager,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ChatUiState())
        val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()


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


        private var defaultRouteForNewChats = RoutingOverride.AUTO

        /** Settings → Local "Allow internet access": gates web tools on on-device runs. */
        private var localInternetAccess = true

        /** Handle to the in-flight streaming request, used by [cancelStreaming]. */
        private var streamJob: Job? = null

        /** Active conversation observer — cancelled when switching conversations. */
        private var messagesJob: Job? = null

        /** Bridges the engine's [ConfirmationGate] to the UI: completed by [respondToConfirmation]. */
        private var pendingGate: CompletableDeferred<Boolean>? = null

        private val contextManager = ContextManager()

        private suspend fun buildMemoryContext(): String? =
            conversationContextManager.buildMemoryContext(_uiState.value.activeRoute)

        private fun buildAssistantSystemPrompt(memoryContext: String?): String =
            conversationContextManager.buildAssistantSystemPrompt(memoryContext)

        private suspend fun extractAndSaveLearnedContext(userText: String) {
            conversationContextManager.extractAndSaveLearnedContext(userText)
        }

        init {
            viewModelScope.launch(dispatchers.main) {
                val conversationId = savedStateHandle.get<String>(Routes.CHAT_ARG_CONVERSATION_ID)
                openConversation(conversationId)
            }



            viewModelScope.launch(dispatchers.io) {
                runCatching { conversationRepository.deleteEmptyConversations(DEFAULT_CONVERSATION_TITLE) }
            }

            viewModelScope.launch(dispatchers.main) {
                providerManager.providers.collectLatest { providers ->
                    activeProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
                    refreshSendEnabled()
                }
            }


            viewModelScope.launch(dispatchers.main) {
                localModelStore.status.collect { _ -> refreshSendEnabled() }
            }



            viewModelScope.launch(dispatchers.main) {
                userPreferences.agentStepCap.collect { cap -> agentStepCap = cap }
            }

            viewModelScope.launch(dispatchers.main) {
                userPreferences.thinkMode.collect { mode ->
                    thinkMode = mode
                    _uiState.update { it.copy(thinkMode = mode) }
                }
            }

            viewModelScope.launch(dispatchers.main) {
                userPreferences.cautiousModeEnabled.collect { enabled -> cautiousMode = enabled }
            }


            viewModelScope.launch(dispatchers.main) {
                userPreferences.chatMode.collect { mode ->
                    defaultRouteForNewChats =
                        if (mode == ChatMode.LOCAL) {
                            RoutingOverride.LOCAL
                        } else {
                            RoutingOverride.AUTO
                        }
                }
            }

            viewModelScope.launch(dispatchers.main) {
                userPreferences.localInternetAccess.collect { allowed -> localInternetAccess = allowed }
            }




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
            sessionApprovedTools.clear()
            val conversation =
                conversationId?.let { conversationRepository.getConversation(it) }

            if (conversation == null) {



                val route = preserveRouting ?: defaultRouteForNewChats
                messagesJob?.cancel()
                _uiState.update {
                    it.copy(
                        conversationId = null,
                        conversationTitle = DEFAULT_CONVERSATION_TITLE,
                        messages = emptyList(),
                        routingOverride = route,


                        activeRoute =
                            RoutingClassifier
                                .classify("", route, localModelReady, isOnline())
                                .route,
                        routeBadge = null,
                        isLoadingConversation = false,
                    )
                }
                return
            }

            _uiState.update {
                it.copy(
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    routingOverride = conversation.routingOverride,
                    activeRoute =
                        RoutingClassifier
                            .classify("", conversation.routingOverride, localModelReady, isOnline())
                            .route,
                    routeBadge = null,
                    isLoadingConversation = false,
                )
            }
            observeMessages(conversation.id)




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

        /** Exactly one messages observer per ViewModel; cancel the previous collector first. */
        private suspend fun observeMessages(conversationId: String) {
            messagesJob?.cancel()
            messagesJob =
                viewModelScope.launch(dispatchers.main) {
                    conversationRepository.observeMessages(conversationId).collectLatest { messages ->
                        _uiState.update { state -> state.copy(messages = messages) }
                    }
                }
            messagesJob?.join()
        }


        private suspend fun ensureConversation(): String {
            _uiState.value.conversationId?.let { return it }
            val created = createConversation(_uiState.value.routingOverride)
            _uiState.update { it.copy(conversationId = created.id) }
            observeMessages(created.id)
            return created.id
        }

        /** Rebuild route badge from persisted reason — model/provider not stored, so label is generic. */
        private fun badgeFromPersistedReason(reasonName: String): RouteBadge? =
            when (runCatching { RoutingReason.valueOf(reasonName) }.getOrNull()) {
                RoutingReason.FORCED_LOCAL,
                RoutingReason.PRIVACY_LOCAL,
                RoutingReason.LIGHT_LOCAL,
                RoutingReason.PROFILE_PRIVATE_LOCAL,
                -> RouteBadge(RoutingOverride.LOCAL, "On-device")

                RoutingReason.FORCED_CLOUD,
                RoutingReason.REALTIME_CLOUD,
                RoutingReason.HEAVY_GENERATIVE_CLOUD,
                RoutingReason.DEFAULT_CLOUD,
                RoutingReason.FORCED_LOCAL_FALLBACK,
                RoutingReason.VISION_CLOUD,
                RoutingReason.CONTEXT_LIMIT_CLOUD,
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

        /** Persist routing override with the conversation (nothing to persist while the chat is still unsaved). */
        fun setRoutingOverride(override: RoutingOverride) {
            val conversationId = _uiState.value.conversationId
            viewModelScope.launch(dispatchers.main) {
                conversationId?.let { id ->
                    conversationRepository.getConversation(id)?.let { current ->
                        conversationRepository.upsertConversation(current.copy(routingOverride = override))
                    }
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

            _uiState.update { it.copy(conversationTitle = title) }
        }

        fun onTextChange(text: String) {
            _uiState.update { it.copy(composerText = text) }
        }

        fun sendMessage(overrideText: String? = null) {
            val state = _uiState.value
            val text = (overrideText ?: state.composerText).trim()
            if (text.isEmpty() || state.isPreparingSend || state.isLoadingConversation) return

            // Sending mid-generation interrupts the in-flight run. The gate is
            // resolved (not parked) so a queued tool call can't fire later
            // against the superseded turn.
            val interrupting = state.isStreaming
            if (interrupting) {
                streamJob?.cancel()
                streamJob = null
                pendingGate?.complete(false)
                pendingGate = null
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isAgentRunning = false,
                        pendingConfirmation = null,
                        agentSteps = emptyList(),
                    )
                }
            }
            streamJob?.cancel()
            streamJob =
                viewModelScope.launch(dispatchers.main) {
                    // Preserve the interrupted run's partial reply the way the Stop
                    // button does — from this live coroutine, because the cancelled
                    // run's own persist can no longer suspend. Runs before any new
                    // upserts, so it can only ever match the superseded message.
                    if (interrupting) {
                        val interrupted =
                            _uiState.value.messages.lastOrNull { it.status == MessageStatus.STREAMING }
                        if (interrupted != null) {
                            conversationRepository.upsertMessage(
                                interrupted.copy(status = MessageStatus.STOPPED),
                            )
                        }
                    }


                    val conversationId = ensureConversation()


                    val decision = classifyRoute(state.routingOverride, text)
                    val target = decision.route
                    if (decision.reason == RoutingReason.FORCED_LOCAL_FALLBACK) {
                        _uiEvents.tryEmit(ChatUiEvent.ShowNotice(LOCAL_UNAVAILABLE_NOTICE))
                    }


                    lastRouteReason = decision.reason.name

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
                        viewModelScope.launch(dispatchers.io) { extractAndSaveLearnedContext(text) }
                        autoTitleConversation(conversationId, text)
                        _uiState.update {
                            if (overrideText == null) {
                                it.copy(composerText = "", isStreaming = true)
                            } else {
                                it.copy(isStreaming = true)
                            }
                        }


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

                    val userMessage =
                        Message(
                            conversationId = conversationId,
                            role = MessageRole.USER,
                            content = text,
                            routeUsed = lastRouteReason,
                        )
                    conversationRepository.upsertMessage(userMessage)
                    viewModelScope.launch(dispatchers.io) { extractAndSaveLearnedContext(text) }
                    autoTitleConversation(conversationId, text)
                    _uiState.update {
                        if (overrideText == null) {
                            it.copy(composerText = "", isStreaming = true)
                        } else {
                            it.copy(isStreaming = true)
                        }
                    }


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

        private val sessionApprovedTools = mutableSetOf<String>()

        /** Resolve a parked tool call from the UI. */
        fun respondToConfirmation(allow: Boolean, alwaysForChat: Boolean = false) {
            val pendingTool = _uiState.value.pendingConfirmation?.toolName
            if (allow && alwaysForChat && pendingTool != null) {
                sessionApprovedTools.add(pendingTool)
            }
            _uiState.update { it.copy(pendingConfirmation = null) }
            pendingGate?.complete(allow)
            pendingGate = null
        }

        fun deleteMessage(messageId: String) {
            viewModelScope.launch(dispatchers.io) {
                runCatching {
                    conversationRepository.deleteMessage(messageId)
                }.onFailure { error ->
                    _uiEvents.tryEmit(ChatUiEvent.ShowError("Could not delete message: ${error.message}"))
                }
            }
        }

        fun editMessage(message: Message) {
            _uiState.update { it.copy(composerText = message.content) }
        }

        fun retryMessage(messageId: String) {
            val state = _uiState.value
            if (state.isStreaming || state.isPreparingSend) return
            viewModelScope.launch(dispatchers.main) {
                conversationRepository.deleteMessage(messageId)
                regenerate()
            }
        }

        fun continueGenerating() {
            val state = _uiState.value
            if (state.isStreaming || state.isPreparingSend) return
            sendMessage("Please continue your response exactly where you left off.")
        }

        private suspend fun streamAgentReply(
            conversationId: String,
            provider: com.jarvis.core.network.LlmProvider,
            model: String,
            reasoningRequested: Boolean = false,
        ) {
            _uiState.update { it.copy(isAgentRunning = true, agentSteps = emptyList()) }
            val history = conversationRepository.getMessages(conversationId)


            val webAllowed =
                _uiState.value.activeRoute != RoutingOverride.LOCAL || localInternetAccess
            val disabledToolNames = if (webAllowed) emptySet() else WebTools.manifestNames.toSet()
            val runner =
                AgentRunner(
                    registry = toolRegistry,
                    audit = auditLogger,
                    confirmationGate = ConfirmationGate { name, argsJson -> awaitConfirmation(name, argsJson) },
                    toolPolicy = DefaultToolPolicy(disabledTools = disabledToolNames),
                    stepCap = agentStepCap ?: AgentRunner.DEFAULT_STEP_CAP,
                    forceConfirm = cautiousMode,
                    disabledTools = disabledToolNames,
                )
            val memoryContext = buildMemoryContext()

            val planFirst = userPreferences.planFirstMode.firstOrNull() ?: false

            val request =
                AgentRunRequest(
                    provider = provider,
                    modelId = model,
                    messages = history,
                    reasoningRequested = reasoningRequested,
                    memoryContext = memoryContext,
                    planFirst = planFirst,
                )


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
                runner.run(request).collect { event ->
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
            } catch (t: Throwable) {



                completeRunning(AgentStepState.FAILED, "Agent failed", t.message?.take(OBSERVATION_PREVIEW_CHARS))
                _uiEvents.tryEmit(ChatUiEvent.ShowError(t.message ?: "Agent run failed"))
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
            if (sessionApprovedTools.contains(toolName)) {
                return true
            }
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
            val allMessages = conversationRepository.getMessages(conversationId)
            val cleanHistory = allMessages.filterNot { it.role == MessageRole.TOOL }
            val compacted = contextManager.compactHistory(cleanHistory, historyTokenBudget = 3200)

            val memoryContext = buildMemoryContext()
            val systemPrompt = buildAssistantSystemPrompt(memoryContext)

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
                    conversationHistory = compacted.messages,
                    model = model,
                    systemPrompt = systemPrompt,
                    thinkMode = thinkMode,
                    reasoningRequested = reasoningRequested,
                )


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
                        ChatStreamEvent.Done -> Unit
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
                val categorized = CategorizedProviderError.classify(error.code, error.message)
                conversationRepository.upsertMessage(
                    assistantMessage.copy(
                        content = text.toString(),
                        reasoningContent = reasoning.toString().ifBlank { null },
                        status = MessageStatus.ERROR,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        errorHint = "${categorized.title}: ${categorized.description}",
                    ),
                )
                _uiState.update { it.copy(isStreaming = false) }
                _uiEvents.tryEmit(ChatUiEvent.ShowError("${categorized.title}: ${categorized.description}"))
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
            voiceManager.toggleRecording(
                scope = viewModelScope,
                mainDispatcher = dispatchers.main,
                ioDispatcher = dispatchers.io,
                isCurrentlyRecording = _uiState.value.isRecording,
                onRecordingChanged = { recording -> _uiState.update { it.copy(isRecording = recording) } },
                onTranscribingChanged = { transcribing -> _uiState.update { it.copy(isTranscribing = transcribing) } },
                onPartialText = { partial -> _uiState.update { it.copy(composerText = partial) } },
                onFinalText = { text, autoSend ->
                    _uiState.update { it.copy(composerText = text) }
                    if (autoSend) sendMessage()
                },
                onError = { message -> _uiEvents.tryEmit(ChatUiEvent.ShowError(message)) },
            )
        }

        /** Release mic resources without sending. */
        fun stopLiveSessionAndRecorder() {
            voiceManager.stopLiveSessionAndRecorder(
                scope = viewModelScope,
                mainDispatcher = dispatchers.main,
                onResetState = {
                    if (_uiState.value.isRecording) {
                        _uiState.update { it.copy(isRecording = false, isTranscribing = false) }
                    }
                },
            )
        }

        /** Play TTS for the most recent assistant response. */
        fun speakLastResponse() {
            val lastAssistant =
                _uiState.value.messages
                    .lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotEmpty() }
                    ?: return
            speakMessage(lastAssistant.id, lastAssistant.content)
        }

        fun speakMessage(messageId: String, content: String) {
            voiceManager.speakMessage(
                messageId = messageId,
                content = content,
                currentPlayingId = _uiState.value.playingAudioMessageId,
                scope = viewModelScope,
                mainDispatcher = dispatchers.main,
                onPlayingChanged = { id -> _uiState.update { it.copy(playingAudioMessageId = id) } },
                onError = { message -> _uiEvents.tryEmit(ChatUiEvent.ShowError(message)) },
            )
        }

        fun stopSpeaking() {
            voiceManager.stopSpeaking { id -> _uiState.update { it.copy(playingAudioMessageId = id) } }
        }

        override fun onCleared() {
            voiceManager.release(viewModelScope, dispatchers.main)
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
