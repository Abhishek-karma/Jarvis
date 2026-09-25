package com.jarvis.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AssistantGoal
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.ContextManager
import com.jarvis.core.agent.DefaultToolPolicy
import com.jarvis.core.agent.GoalEngine
import com.jarvis.core.agent.GoalEvent
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.tools.WebTools
import com.jarvis.core.common.Conversation
import com.jarvis.core.common.DEFAULT_CONVERSATION_TITLE
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.database.repository.ConversationRepository
import com.jarvis.core.navigation.Routes
import com.jarvis.core.preferences.ChatMode
import com.jarvis.core.preferences.UserPreferencesRepository
import com.jarvis.core.voice.VoiceSessionState
import com.jarvis.feature.chat.components.AssistantActionFormatter
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
        private val userPreferences: UserPreferencesRepository,
        private val conversationContextManager: ConversationContextManager,
        private val goalEngine: GoalEngine,
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

        private var defaultRouteForNewChats = RoutingOverride.CLOUD

        /** Handle to the in-flight streaming request, used by [cancelStreaming]. */
        private var streamJob: Job? = null

        /** Active conversation observer — cancelled when switching conversations. */
        private var messagesJob: Job? = null

        /** Bridges the engine's [ConfirmationGate] to the UI: completed by [respondToConfirmation]. */
        private var pendingGate: CompletableDeferred<Boolean>? = null

        fun createConfirmationGate(): ConfirmationGate = ConfirmationGate { toolName, argsJson ->
            awaitConfirmation(toolName, argsJson)
        }

        private val contextManager = ContextManager()

        private suspend fun buildMemoryContext(userQuery: String? = null): String? =
            conversationContextManager.buildMemoryContext(_uiState.value.activeRoute, userQuery)

        private fun buildAssistantSystemPrompt(
            memoryContext: String?,
            isVoiceMode: Boolean = false,
            planFirst: Boolean = false,
            webToolsAvailable: Boolean = true,
        ): String =
            conversationContextManager.buildAssistantSystemPrompt(
                memoryContext = memoryContext,
                isVoiceMode = isVoiceMode,
                planFirst = planFirst,
                webToolsAvailable = webToolsAvailable,
            )

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
                userPreferences.chatMode.collect {
                    defaultRouteForNewChats = RoutingOverride.CLOUD
                }
            }

            viewModelScope.launch(dispatchers.main) {
                voiceManager.voiceState.collect { state ->
                    _uiState.update { current ->
                        val updatedStatus = when {
                            state is VoiceSessionState.Speaking -> AgentStatus.SPEAKING
                            current.agentStatus == AgentStatus.SPEAKING && state !is VoiceSessionState.Speaking -> AgentStatus.COMPLETED
                            else -> current.agentStatus
                        }
                        current.copy(
                            voiceState = state,
                            agentStatus = updatedStatus,
                        )
                    }
                }
            }

            viewModelScope.launch(dispatchers.main) {
                voiceManager.isVoiceModeActive.collect { active ->
                    _uiState.update { it.copy(isVoiceModeActive = active) }
                }
            }

            viewModelScope.launch(dispatchers.main) {
                voiceManager.isSpeakerMuted.collect { muted ->
                    _uiState.update { it.copy(isSpeakerMuted = muted) }
                }
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
                it.copy(isSendingEnabled = activeProvider != null)
            }
        }

        private suspend fun openConversation(conversationId: String?, preserveRouting: RoutingOverride? = null) {
            cancelStreaming()
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
                        activeRoute = RoutingOverride.CLOUD,
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
                    activeRoute = RoutingOverride.CLOUD,
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
        private fun observeMessages(conversationId: String) {
            messagesJob?.cancel()
            messagesJob =
                viewModelScope.launch(dispatchers.main) {
                    conversationRepository.observeMessages(conversationId).collectLatest { messages ->
                        _uiState.update { state -> state.copy(messages = messages) }
                    }
                }
        }

        private suspend fun ensureConversation(): String {
            _uiState.value.conversationId?.let { return it }
            val created = createConversation(_uiState.value.routingOverride)
            _uiState.update { it.copy(conversationId = created.id) }
            observeMessages(created.id)
            return created.id
        }

        /** Rebuild route badge from persisted reason. */
        private fun badgeFromPersistedReason(reasonName: String): RouteBadge? =
            when (reasonName.uppercase(java.util.Locale.US)) {
                "DEFAULT_CLOUD" -> RouteBadge(RoutingOverride.CLOUD, "Cloud")
                else -> null // Unknown routing reason: show no badge rather than a false one.
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
                        activeRoute = RoutingOverride.CLOUD,
                    )
                }
            }
        }

        /** Persist reasoning-effort mode. */
        fun setThinkMode(mode: ThinkMode) {
            viewModelScope.launch(dispatchers.main) {
                userPreferences.setThinkMode(mode)
            }
        }

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
            if (state.pendingConfirmation != null) return
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
                    lastRouteReason = "DEFAULT_CLOUD"

                    val provider = activeProvider
                    if (provider == null) {
                        _uiEvents.tryEmit(
                            ChatUiEvent.ShowNotice(
                                "No cloud provider configured. Add one in Settings.",
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
                                "No model available for \"${provider.name}\". Set a model in the provider settings.",
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

                    streamGoalExecution(
                        conversationId,
                        text,
                        providerAdapter,
                        model,
                        reasoningRequested = ThinkModeHeuristic.shouldThink(text, thinkMode),
                    )
                }
        }

        /** Cancel streaming — partial response preserved. */
        fun cancelStreaming() {
            streamJob?.cancel()
            streamJob = null
            pendingGate?.complete(false)
            pendingGate = null
            viewModelScope.launch(dispatchers.main) {
                val streamingMessage = _uiState.value.messages.lastOrNull { it.status == MessageStatus.STREAMING }
                if (streamingMessage != null) {
                    conversationRepository.upsertMessage(
                        streamingMessage.copy(status = MessageStatus.STOPPED),
                    )
                }
                val updatedSteps = _uiState.value.agentSteps.map {
                    if (it.state == AgentStepState.RUNNING) it.copy(state = AgentStepState.CANCELLED) else it
                }
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isAgentRunning = false,
                        agentStatus = if (it.isAgentRunning || it.agentStatus.isActive) AgentStatus.CANCELLED else it.agentStatus,
                        pendingConfirmation = null,
                        agentSteps = updatedSteps,
                    )
                }
                voiceManager.onAgentCancelled()
            }
        }

        /** Regenerate the last response. */
        fun regenerate() {
            val state = _uiState.value
            val conversationId = state.conversationId ?: return
            if (state.isStreaming || state.isPreparingSend || state.isLoadingConversation || state.pendingConfirmation != null) return

            val lastUserIndex = state.messages.indexOfLast { it.role == MessageRole.USER }
            if (lastUserIndex < 0) return

            streamJob?.cancel()
            streamJob =
                viewModelScope.launch(dispatchers.main) {

                    val lastUser = state.messages[lastUserIndex]
                    val toRemove = state.messages.drop(lastUserIndex + 1)
                    toRemove.forEach { conversationRepository.deleteMessage(it.id) }

                    lastRouteReason = "DEFAULT_CLOUD"

                    val provider = activeProvider
                    if (provider == null) {
                        _uiEvents.tryEmit(
                            ChatUiEvent.ShowNotice(
                                "No cloud provider configured. Add one in Settings.",
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
                                "No model available for \"${provider.name}\". Set a model in the provider settings.",
                            ),
                        )
                        return@launch
                    }

                    _uiState.update { it.copy(routeBadge = RouteBadge(RoutingOverride.CLOUD, "$model • ${provider.name}")) }

                    _uiState.update { it.copy(isStreaming = true) }
                    streamGoalExecution(
                            conversationId,
                            lastUser.content,
                            providerAdapter,
                            model,
                            reasoningRequested = ThinkModeHeuristic.shouldThink(lastUser.content, thinkMode),
                        )
                }
        }

        private val sessionApprovedTools = mutableSetOf<String>()

        private fun isSensitiveTool(toolName: String): Boolean {
            val tier = toolRegistry.get(toolName)?.tier
            return tier == PermissionTier.SENSITIVE ||
                toolName == "place_call" ||
                toolName == "send_sms"
        }

        /** Resolve a parked tool call from the UI. */
        fun respondToConfirmation(allow: Boolean, alwaysForChat: Boolean = false) {
            val pendingTool = _uiState.value.pendingConfirmation?.toolName
            if (allow && alwaysForChat && pendingTool != null && !isSensitiveTool(pendingTool)) {
                sessionApprovedTools.add(pendingTool)
            }
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    agentStatus = if (allow) AgentStatus.RUNNING_TOOL else AgentStatus.CANCELLED,
                )
            }
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
            if (state.isStreaming || state.isPreparingSend || state.pendingConfirmation != null) return
            viewModelScope.launch(dispatchers.main) {
                conversationRepository.deleteMessage(messageId)
                regenerate()
            }
        }

        fun continueGenerating() {
            val state = _uiState.value
            if (state.isStreaming || state.isPreparingSend || state.pendingConfirmation != null) return
            sendMessage("Please continue your response exactly where you left off.")
        }

        /** Tracks the in-progress agent step list and its shared publish/milestone logic. */
        private inner class StepTracker(private val conversationId: String) {
            val steps = mutableListOf<AgentStep>()
            private var runningSinceMs = System.currentTimeMillis()

            fun publish() = _uiState.update { it.copy(agentSteps = steps.toList()) }

            fun updateRunning(text: String) {
                val index = steps.indexOfLast { it.state == AgentStepState.RUNNING }
                if (index >= 0) {
                    steps[index] = steps[index].copy(text = text)
                    publish()
                }
            }

            /** Finish the running row. */
            fun completeRunning(
                state: AgentStepState,
                text: String? = null,
                detail: String? = null,
            ) {
                val index = steps.indexOfLast { it.state == AgentStepState.RUNNING }
                if (index >= 0) {
                    val finished = steps[index]
                    steps[index] = finished.copy(
                        text = text ?: finished.text,
                        state = state,
                        detail = detail ?: finished.detail,
                        durationLabel = formatAgentDuration(System.currentTimeMillis() - runningSinceMs),
                    )
                    publish()
                }
            }

            fun push(text: String, toolName: String? = null) {
                runningSinceMs = System.currentTimeMillis()
                steps += AgentStep(text = text, toolName = toolName)
                publish()
            }
        }

        /** Replace (or append) an in-flight streaming message in `_uiState.messages` with a single list pass. */
        private fun updateStreamingMessage(updated: Message) {
            _uiState.update { state ->
                var found = false
                val newMessages = state.messages.map { if (it.id == updated.id) { found = true; updated } else it }
                state.copy(messages = if (found) newMessages else state.messages + updated, isStreaming = true)
            }
        }

        private suspend fun awaitConfirmation(
            toolName: String,
            argsJson: String,
        ): Boolean {
            if (!isSensitiveTool(toolName) && sessionApprovedTools.contains(toolName)) {
                return true
            }
            val gate = CompletableDeferred<Boolean>()
            pendingGate = gate
            _uiState.update {
                it.copy(
                    agentStatus = AgentStatus.WAITING_FOR_APPROVAL,
                    pendingConfirmation = AgentConfirmation(toolName, argsJson),
                )
            }
            if (voiceManager.isVoiceModeActive.value) {
                voiceManager.onAgentWaitingForApproval(toolName, argsJson)
                val question = voiceManager.getConfirmationQuestion(toolName, argsJson)
                speakConfirmationQuestion(question)
            }
            return gate.await()
        }

        private fun speakConfirmationQuestion(question: String) {
            voiceManager.speakAssistantResponse(
                text = question,
                scope = viewModelScope,
                mainDispatcher = dispatchers.main,
                onUserSpeechFinal = { response ->
                    handleConfirmationVoiceInput(response)
                },
                onError = { message ->
                    _uiEvents.tryEmit(ChatUiEvent.ShowError(message))
                }
            )
        }

        private fun handleConfirmationVoiceInput(response: String) {
            val clean = response.lowercase().trim()
            val positiveWords = listOf("yes", "yeah", "sure", "do it", "confirm", "allow", "okay", "ok", "go ahead")
            val negativeWords = listOf("no", "nope", "cancel", "deny", "don't", "dont", "stop")

            val isPositive = positiveWords.any { clean.contains(it) }
            val isNegative = negativeWords.any { clean.contains(it) }

            when {
                isPositive -> {
                    respondToConfirmation(allow = true)
                }
                isNegative -> {
                    respondToConfirmation(allow = false)
                }
                else -> {
                    speakConfirmationQuestion("Sorry, should I do that?")
                }
            }
        }

        private fun formatAgentDuration(elapsedMs: Long): String =
            String.format(Locale.US, "%.1fs", elapsedMs.coerceAtLeast(0) / 1000.0)

        /** Persist a completed action milestone as a display-only TOOL row. */
        private suspend fun persistMilestone(
            conversationId: String,
            text: String,
            timestamp: Long = System.currentTimeMillis(),
        ): Long {
            val clean = text.removePrefix("✓").trim()
            if (clean.isBlank() ||
                clean.equals("Done", ignoreCase = true) ||
                clean.equals("Completed", ignoreCase = true) ||
                clean.equals("Success", ignoreCase = true)
            ) return timestamp
            conversationRepository.upsertMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.TOOL,
                    content = clean,
                    status = MessageStatus.COMPLETE,
                    createdAt = timestamp,
                ),
            )
            return timestamp
        }

        private suspend fun streamGoalExecution(
            conversationId: String,
            text: String,
            provider: com.jarvis.core.network.LlmProvider,
            model: String,
            reasoningRequested: Boolean = false,
        ) {
            val planFirst = userPreferences.planFirstMode.firstOrNull() ?: false
            _uiState.update {
                it.copy(
                    isAgentRunning = true,
                    agentStatus = if (planFirst) AgentStatus.PLANNING else AgentStatus.THINKING,
                    runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                    userFacingState = null,
                    executionResult = null,
                    agentFailureReason = null,
                    agentSteps = emptyList(),
                )
            }
            val history = conversationRepository.getMessages(conversationId)
            val memoryContext = buildMemoryContext(history.lastOrNull { it.role == MessageRole.USER }?.content)

            val goal = AssistantGoal(
                id = java.util.UUID.randomUUID().toString(),
                goalDescription = text,
                source = "chat",
                conversationId = conversationId,
                messages = history,
                provider = provider,
                modelId = model,
                reasoningRequested = reasoningRequested,
                memoryContext = memoryContext,
                planFirst = planFirst,
                isVoiceMode = _uiState.value.isVoiceModeActive,
                confirmationGate = ConfirmationGate { name, argsJson -> awaitConfirmation(name, argsJson) },
                forceConfirm = cautiousMode,
            )

            val steps = StepTracker(conversationId)

            var assistantText = ""
            var streamingAssistant: Message? = null
            var streamTextBuilder: StringBuilder? = null
            var lastStreamPersistNanos = System.nanoTime()
            var latestMilestoneTimestamp = 0L

            suspend fun flushStreamingMessageStopped() {
                streamingAssistant?.takeIf { it.content.isNotBlank() }?.let {
                    conversationRepository.upsertMessage(it.copy(status = MessageStatus.STOPPED))
                }
            }

            try {
                goalEngine.executeGoal(goal).collect { event ->
                    when (event) {
                        is GoalEvent.StatusChanged -> {
                            _uiState.update { state ->
                                state.copy(
                                    agentStatus = if (state.isAgentRunning) AgentStatus.RUNNING_TOOL else state.agentStatus,
                                    runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                                )
                            }
                        }
                        is GoalEvent.PlanGenerated -> Unit
                        is GoalEvent.MilestoneReached -> {
                            val time = System.currentTimeMillis()
                            latestMilestoneTimestamp = maxOf(latestMilestoneTimestamp, time)
                            persistMilestone(conversationId, event.description, time)
                        }
                        is GoalEvent.AgentEvent -> {
                            when (val agentEvent = event.event) {
                                is AgentEvent.TextDelta -> {
                                    if (streamingAssistant == null) {
                                        val streamCreatedAt = maxOf(latestMilestoneTimestamp + 1, System.currentTimeMillis())
                                        streamingAssistant = Message(
                                            conversationId = conversationId,
                                            role = MessageRole.ASSISTANT,
                                            content = "",
                                            status = MessageStatus.STREAMING,
                                            routeUsed = lastRouteReason,
                                            createdAt = streamCreatedAt,
                                        )
                                        conversationRepository.upsertMessage(streamingAssistant!!)
                                        streamTextBuilder = StringBuilder()
                                        if (voiceManager.isVoiceModeActive.value) {
                                            voiceManager.prepareStreamingResponse(
                                                scope = viewModelScope,
                                                mainDispatcher = dispatchers.main,
                                                onUserSpeechFinal = { text ->
                                                    _uiState.update { it.copy(composerText = text) }
                                                    sendMessage()
                                                },
                                                onError = { message ->
                                                    _uiEvents.tryEmit(ChatUiEvent.ShowError(message))
                                                },
                                            )
                                        }
                                    }
                                    streamTextBuilder!!.append(agentEvent.text)
                                    streamingAssistant = streamingAssistant!!.copy(content = streamTextBuilder.toString())
                                    updateStreamingMessage(streamingAssistant!!)
                                    if (voiceManager.isVoiceModeActive.value) {
                                        voiceManager.onStreamingToken(agentEvent.text)
                                    }
                                    if (System.nanoTime() - lastStreamPersistNanos >= PERSIST_DEBOUNCE_NS) {
                                        lastStreamPersistNanos = System.nanoTime()
                                        conversationRepository.upsertMessage(streamingAssistant!!)
                                    }
                                }
                                is AgentEvent.RunStarted -> {
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = if (planFirst) AgentStatus.PLANNING else AgentStatus.THINKING,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                                        )
                                    }
                                }
                                is AgentEvent.IterationStarted -> {
                                    if (agentEvent.step > 1) {
                                        _uiState.update {
                                            it.copy(
                                                agentStatus = AgentStatus.THINKING_AGAIN,
                                                runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                                            )
                                        }
                                        voiceManager.onAgentPlanning()
                                    }
                                }
                                is AgentEvent.ToolRequested -> {
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.SELECTING_TOOL,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                                        )
                                    }
                                    steps.completeRunning(AgentStepState.DONE)
                                    steps.push("Calling ${agentEvent.name}", toolName = agentEvent.name)
                                    voiceManager.onAgentExecuting(agentEvent.name, "")
                                    if (streamingAssistant != null) {
                                        val oldId = streamingAssistant!!.id
                                        streamingAssistant = null
                                        streamTextBuilder = null
                                        conversationRepository.deleteMessage(oldId)
                                        _uiState.update { it.copy(messages = it.messages.filterNot { m -> m.id == oldId }) }
                                    }
                                }
                                is AgentEvent.ConfirmationRequired -> {
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.WAITING_FOR_APPROVAL,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.WAITING_FOR_CONFIRMATION,
                                            pendingConfirmation = AgentConfirmation(agentEvent.name, agentEvent.argsJson),
                                        )
                                    }
                                    steps.updateRunning("Needs your approval: ${agentEvent.name}")
                                    voiceManager.onAgentWaitingForApproval(agentEvent.name, agentEvent.argsJson)
                                }
                                is AgentEvent.ToolExecuting -> {
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.RUNNING_TOOL,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                                        )
                                    }
                                    steps.updateRunning("Running ${agentEvent.name}…")
                                    voiceManager.onAgentExecuting(agentEvent.name, "")
                                }
                                is AgentEvent.ToolExecuted -> {
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.READING_RESULT,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                                        )
                                    }
                                    if (streamingAssistant != null) {
                                        val oldId = streamingAssistant!!.id
                                        streamingAssistant = null
                                        streamTextBuilder = null
                                        conversationRepository.deleteMessage(oldId)
                                        _uiState.update { it.copy(messages = it.messages.filterNot { m -> m.id == oldId }) }
                                    }
                                    if (agentEvent.success) {
                                        val milestoneDesc = AssistantActionFormatter.toCompletedDescription(agentEvent.name, agentEvent.observationText)
                                        if (milestoneDesc != null) {
                                            val time = System.currentTimeMillis()
                                            latestMilestoneTimestamp = maxOf(latestMilestoneTimestamp, time)
                                            persistMilestone(conversationId, milestoneDesc, time)
                                        }
                                    }
                                    steps.completeRunning(
                                        state = if (agentEvent.success) AgentStepState.DONE else AgentStepState.FAILED,
                                        text = if (agentEvent.success) "${agentEvent.name} done" else "${agentEvent.name} failed",
                                        detail = agentEvent.observationText.take(OBSERVATION_PREVIEW_CHARS).ifBlank { null },
                                    )
                                }
                                is AgentEvent.ToolRejected -> {
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.READING_RESULT,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.RUNNING,
                                        )
                                    }
                                    steps.completeRunning(
                                        state = AgentStepState.FAILED,
                                        text = "Rejected ${agentEvent.name}",
                                        detail = agentEvent.reason.take(OBSERVATION_PREVIEW_CHARS).ifBlank { null },
                                    )
                                }
                                is AgentEvent.ToolCancelled -> {
                                    steps.completeRunning(AgentStepState.CANCELLED, "Denied ${agentEvent.name}")
                                }
                                is AgentEvent.ProgressMilestone -> {
                                    val time = System.currentTimeMillis()
                                    latestMilestoneTimestamp = maxOf(latestMilestoneTimestamp, time)
                                    persistMilestone(conversationId, agentEvent.message, time)
                                }
                                is AgentEvent.FinalAnswer -> {
                                    assistantText = agentEvent.text
                                    val current = streamingAssistant
                                    if (current != null && agentEvent.text.length >= current.content.length && current.content != agentEvent.text) {
                                        streamingAssistant = current.copy(content = agentEvent.text)
                                        updateStreamingMessage(streamingAssistant!!)
                                    }
                                }
                                is AgentEvent.Failed -> {
                                    val uf = agentEvent.userFacingState
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.FAILED,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.FAILED,
                                            userFacingState = uf,
                                            agentFailureReason = uf.message,
                                        )
                                    }
                                }
                                is AgentEvent.Cancelled -> {
                                    val uf = agentEvent.userFacingState
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.CANCELLED,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.CANCELLED,
                                            userFacingState = uf,
                                        )
                                    }
                                }
                                is AgentEvent.StepCapReached -> {
                                    _uiState.update {
                                        it.copy(
                                            agentStatus = AgentStatus.FAILED,
                                            runState = com.jarvis.core.agent.execution.AgentRunState.FAILED,
                                        )
                                    }
                                }
                            }
                        }
                        is GoalEvent.ConfirmationRequired -> {
                            _uiState.update {
                                it.copy(
                                    agentStatus = AgentStatus.WAITING_FOR_APPROVAL,
                                    runState = com.jarvis.core.agent.execution.AgentRunState.WAITING_FOR_CONFIRMATION,
                                    pendingConfirmation = AgentConfirmation(event.toolName, event.argsJson),
                                )
                            }
                        }
                        is GoalEvent.Completed -> {
                            steps.completeRunning(AgentStepState.DONE)
                            val completedResult = event.executionResult
                            val summaryText =
                                assistantText.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                                    ?: event.summary.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                                    ?: completedResult?.userMessage?.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                                    ?: completedResult?.message?.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                                    ?: com.jarvis.core.agent.execution.ToolResultResponseDeriver.deriveFallback(completedResult, event.summary)
                            val finalCreatedAt = maxOf(latestMilestoneTimestamp + 1, System.currentTimeMillis())
                            val streaming = streamingAssistant
                            val execRes =
                                completedResult
                                    ?: com.jarvis.core.agent.execution.ExecutionResult.success(
                                        message = summaryText,
                                        userMessage = summaryText,
                                    )
                            if (streaming != null) {
                                // Finalize the existing placeholder. `content` is resolved explicitly so a
                                // placeholder that only ever received whitespace deltas is filled with the
                                // summary instead of rendering an empty assistant bubble.
                                val finalText = streaming.content.takeIf { it.isNotBlank() && !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) } ?: summaryText
                                val final = streaming.copy(
                                    content = finalText,
                                    status = MessageStatus.COMPLETE,
                                    createdAt = maxOf(streaming.createdAt, finalCreatedAt),
                                )
                                conversationRepository.upsertMessage(final)
                                _uiState.update {
                                    it.copy(
                                        messages = it.messages.map { msg -> if (msg.id == final.id) final else msg },
                                        isStreaming = false,
                                        isAgentRunning = false,
                                        agentStatus = AgentStatus.COMPLETED,
                                        runState = com.jarvis.core.agent.execution.AgentRunState.COMPLETED,
                                        userFacingState = execRes.userFacingState,
                                        executionResult = execRes,
                                    )
                                }
                            } else {
                                val assistantMsg = Message(
                                    conversationId = conversationId,
                                    role = MessageRole.ASSISTANT,
                                    content = summaryText,
                                    status = MessageStatus.COMPLETE,
                                    createdAt = finalCreatedAt,
                                )
                                conversationRepository.upsertMessage(assistantMsg)
                                // Dedupe by id: the Room observer may already have emitted this row,
                                // and `visibleMessages` is keyed by id (duplicates would crash the list).
                                _uiState.update { state ->
                                    val existing = state.messages.filterNot { m -> m.id == assistantMsg.id }
                                    state.copy(
                                        messages = (existing + assistantMsg).sortedBy { m -> m.createdAt },
                                        isStreaming = false,
                                        isAgentRunning = false,
                                        agentStatus = AgentStatus.COMPLETED,
                                        runState = com.jarvis.core.agent.execution.AgentRunState.COMPLETED,
                                        userFacingState = execRes.userFacingState,
                                        executionResult = execRes,
                                    )
                                }
                            }
                            if (voiceManager.isVoiceModeActive.value) {
                                if (streamingAssistant != null) {
                                    voiceManager.onStreamingComplete()
                                } else {
                                    voiceManager.speakAssistantResponse(
                                        summaryText,
                                        viewModelScope,
                                        dispatchers.main,
                                        onUserSpeechFinal = { text ->
                                            _uiState.update { it.copy(composerText = text) }
                                            sendMessage()
                                        },
                                        onError = { message ->
                                            _uiEvents.tryEmit(ChatUiEvent.ShowError(message))
                                        },
                                    )
                                }
                            }
                        }
                        is GoalEvent.Failed -> {
                            steps.completeRunning(AgentStepState.FAILED)
                            flushStreamingMessageStopped()
                            val execRes = event.executionResult
                            val userFacing = execRes?.userFacingState ?: com.jarvis.core.agent.execution.ExecutionErrorMapper.map(
                                code = com.jarvis.core.agent.execution.ErrorCode.fromString(event.code),
                                technicalDetail = event.reason,
                            )
                            _uiState.update {
                                it.copy(
                                    isStreaming = false,
                                    isAgentRunning = false,
                                    agentStatus = AgentStatus.FAILED,
                                    runState = com.jarvis.core.agent.execution.AgentRunState.FAILED,
                                    agentFailureReason = userFacing.message,
                                    userFacingState = userFacing,
                                    executionResult = execRes,
                                )
                            }
                            _uiEvents.tryEmit(ChatUiEvent.ShowError(userFacing.message))
                            voiceManager.onAgentError(userFacing.message)
                        }
                        is GoalEvent.Cancelled -> {
                            steps.completeRunning(AgentStepState.CANCELLED)
                            flushStreamingMessageStopped()
                            val userFacing = com.jarvis.core.agent.execution.ExecutionErrorMapper.map(
                                com.jarvis.core.agent.execution.ErrorCode.USER_CANCELLED,
                                event.reason,
                            )
                            _uiState.update {
                                it.copy(
                                    isStreaming = false,
                                    isAgentRunning = false,
                                    agentStatus = AgentStatus.CANCELLED,
                                    runState = com.jarvis.core.agent.execution.AgentRunState.CANCELLED,
                                    userFacingState = userFacing,
                                    executionResult = event.executionResult,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                flushStreamingMessageStopped()
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isAgentRunning = false,
                        agentStatus = AgentStatus.CANCELLED,
                        runState = com.jarvis.core.agent.execution.AgentRunState.CANCELLED,
                        userFacingState = com.jarvis.core.agent.execution.ExecutionErrorMapper.map(com.jarvis.core.agent.execution.ErrorCode.USER_CANCELLED),
                    )
                }
                throw e
            } catch (e: Exception) {
                val userFacing = com.jarvis.core.agent.execution.ExecutionErrorMapper.fromException(e)
                flushStreamingMessageStopped()
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isAgentRunning = false,
                        agentStatus = AgentStatus.FAILED,
                        runState = com.jarvis.core.agent.execution.AgentRunState.FAILED,
                        agentFailureReason = userFacing.message,
                        userFacingState = userFacing,
                    )
                }
                _uiEvents.tryEmit(ChatUiEvent.ShowError(userFacing.message))
                voiceManager.onAgentError(userFacing.message)
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

        fun startVoiceMode() {
            viewModelScope.launch(dispatchers.main) {
                if (_uiState.value.isStreaming) {
                    cancelStreaming()
                }
                if (_uiState.value.conversationId == null) {
                    openConversation(null, preserveRouting = _uiState.value.routingOverride)
                }
                voiceManager.startVoiceMode(
                    scope = viewModelScope,
                    mainDispatcher = dispatchers.main,
                    onUserSpeechFinal = { text ->
                        _uiState.update { it.copy(composerText = text) }
                        sendMessage()
                    },
                    onError = { message ->
                        _uiEvents.tryEmit(ChatUiEvent.ShowError(message))
                    },
                )
            }
        }

        fun stopVoiceMode() {
            voiceManager.stopVoiceMode(
                scope = viewModelScope,
                mainDispatcher = dispatchers.main,
            )
        }

        fun toggleSpeakerMute() {
            voiceManager.toggleSpeakerMute(
                scope = viewModelScope,
                mainDispatcher = dispatchers.main,
                onSpeechResume = {
                    voiceManager.startLiveListening(
                        scope = viewModelScope,
                        mainDispatcher = dispatchers.main,
                        onUserSpeechFinal = { text ->
                            _uiState.update { it.copy(composerText = text) }
                            sendMessage()
                        },
                        onError = { message ->
                            _uiEvents.tryEmit(ChatUiEvent.ShowError(message))
                        },
                    )
                },
            )
        }

        fun interruptSpeaking() {
            cancelStreaming()
            voiceManager.interruptSpeaking(
                scope = viewModelScope,
                mainDispatcher = dispatchers.main,
                onUserSpeechFinal = { text ->
                    _uiState.update { it.copy(composerText = text) }
                    sendMessage()
                },
                onError = { message ->
                    _uiEvents.tryEmit(ChatUiEvent.ShowError(message))
                },
            )
        }

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
            cancelStreaming()
            pendingGate?.complete(false)
            pendingGate = null
            voiceManager.release(viewModelScope, dispatchers.main)
            super.onCleared()
        }

        companion object {
            /** 100ms streaming-persist debounce. */
            const val PERSIST_DEBOUNCE_NS = 100_000_000L

            /** Grace period for a live recognizer to deliver its final result after stop. */
            const val LIVE_RESULT_TIMEOUT_MS = 500L

            /** Canvas detail lines carry an observation preview, never the full raw output. */
            const val OBSERVATION_PREVIEW_CHARS = 140

            /** Auto-derived conversation titles cap here; longer openers are truncated. */
            const val TITLE_MAX_CHARS = 50
        }
    }
