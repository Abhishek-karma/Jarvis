package com.jarvis.core.agent

import android.util.Log
import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.agent.execution.ExecutionErrorMapper
import com.jarvis.core.agent.execution.ExecutionResult
import com.jarvis.core.agent.execution.UserFacingState
import com.jarvis.core.agent.prompt.PromptBuilder
import com.jarvis.core.agent.prompt.PromptConfig
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Input to one agent run: the provider/model and the message history ending with the user's request. */
data class AgentRunRequest(
    val provider: LlmProvider,
    val modelId: String,
    val messages: List<Message>,
    val agentRunId: String? = null,
    val reasoningRequested: Boolean = false,
    val memoryContext: String? = null,
    val planFirst: Boolean = false,
    val isVoiceMode: Boolean = false,
    val systemPromptOverride: String? = null,
    val maxSteps: Int? = null,
    val timeoutMillis: Long? = null,
    val maxSideEffectRepetitions: Int? = null,
    val maxReadOnlyRepetitions: Int? = null,
    val confirmationGate: ConfirmationGate? = null,
    val forceConfirm: Boolean = false,
)

/** Answers whether a pending Sensitive-tier call may proceed. */
fun interface ConfirmationGate {
    suspend fun confirm(
        toolName: String,
        argsJson: String,
    ): Boolean
}

class ConfirmationGateElement(val gate: ConfirmationGate?) : kotlin.coroutines.CoroutineContext.Element {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ConfirmationGateElement>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> = Key
}

class LlmProviderElement(
    val provider: LlmProvider?,
    val modelId: String?,
) : kotlin.coroutines.CoroutineContext.Element {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<LlmProviderElement>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> = Key
}

/** Set by ToolExecutor after approval of the outer phone_agent capability. */
class PhoneAutomationApprovalElement(
    val approved: Boolean,
) : kotlin.coroutines.CoroutineContext.Element {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<PhoneAutomationApprovalElement>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> = Key
}

/** Streamed progress of an agent run. */
sealed class AgentEvent {
    data object RunStarted : AgentEvent()
    data class IterationStarted(val step: Int) : AgentEvent()
    data class ToolRequested(val name: String, val argsJson: String, val tier: PermissionTier) : AgentEvent()
    data class ConfirmationRequired(val name: String, val argsJson: String) : AgentEvent()
    data class ToolExecuting(val name: String) : AgentEvent()
    data class ToolExecuted(
        val name: String,
        val success: Boolean,
        val observationText: String,
        val errorCode: ErrorCode? = null,
        val userFacingState: UserFacingState? = null,
    ) : AgentEvent()
    data class ToolRejected(
        val name: String,
        val reason: String,
        val errorCode: ErrorCode = ErrorCode.POLICY_DENIED,
        val userFacingState: UserFacingState? = null,
    ) : AgentEvent()
    data class ToolCancelled(val name: String) : AgentEvent()
    data class ProgressMilestone(val message: String, val step: Int? = null) : AgentEvent()
    data class TextDelta(val text: String) : AgentEvent()
    data class FinalAnswer(val text: String, val executionResult: ExecutionResult? = null) : AgentEvent()
    data class Failed(
        val code: ErrorCode,
        val message: String,
        val userFacingState: UserFacingState = ExecutionErrorMapper.map(code, message),
    ) : AgentEvent()
    data class Cancelled(
        val reason: String = "Stopped by user",
        val userFacingState: UserFacingState = ExecutionErrorMapper.map(ErrorCode.USER_CANCELLED, reason),
    ) : AgentEvent()
    data class StepCapReached(val stepsUsed: Int) : AgentEvent()
}

/**
 * Single Canonical Agent Loop for Jarvis.
 */
class AgentRunner(
    private val registry: ToolRegistry,
    private val audit: AuditLogger,
    private val confirmationGate: ConfirmationGate,
    private val toolPolicy: ToolPolicy = DefaultToolPolicy(),
    private val stepCap: Int = DEFAULT_STEP_CAP,
    private val forceConfirm: Boolean = false,
    private val disabledTools: Set<String> = emptySet(),
    private val contextManager: ContextManager = ContextManager(),
    private val toolExecutor: ToolExecutor = ToolExecutor(registry, audit, toolPolicy, contextManager),
    private val maxSideEffectRepetitions: Int = DEFAULT_MAX_SIDE_EFFECT_REPETITIONS,
    private val maxReadOnlyRepetitions: Int = DEFAULT_MAX_READ_ONLY_REPETITIONS,
    private val executionTimeoutMillis: Long? = null,
) {
    init {
        require(stepCap in 1..MAX_STEP_CAP) { "stepCap must be within 1..$MAX_STEP_CAP" }
        require(maxSideEffectRepetitions >= 1) { "maxSideEffectRepetitions must be >= 1" }
        require(maxReadOnlyRepetitions >= 1) { "maxReadOnlyRepetitions must be >= 1" }
        if (executionTimeoutMillis != null) {
            require(executionTimeoutMillis > 0) { "executionTimeoutMillis must be > 0" }
        }
    }

    fun run(request: AgentRunRequest): Flow<AgentEvent> = flow {
        emit(AgentEvent.RunStarted)
        val definitions = registry.definitions().filterNot { it.name in disabledTools }
        val supportsTools = request.provider.capabilities.supportsTools && definitions.isNotEmpty()
        val baseHistory = request.messages.filterNot { it.role == MessageRole.TOOL }
        val turnLog = mutableListOf<Message>()
        val completedToolSteps = mutableListOf<String>()
        val executionHistory = mutableListOf<ToolExecutionHistory>()
        var lastToolFailed: Pair<String, ErrorCode>? = null
        var lastAssistantText = ""

        val effectiveTimeout = request.timeoutMillis ?: executionTimeoutMillis
        val effectiveStepCap = (request.maxSteps ?: stepCap).coerceIn(1, MAX_STEP_CAP)
        val effectiveMaxSideEffectReps = (request.maxSideEffectRepetitions ?: maxSideEffectRepetitions).coerceAtLeast(1)
        val effectiveMaxReadOnlyReps = (request.maxReadOnlyRepetitions ?: maxReadOnlyRepetitions).coerceAtLeast(1)
        val effectiveConfirmationGate = request.confirmationGate ?: this@AgentRunner.confirmationGate
        val effectiveForceConfirm = request.forceConfirm || this@AgentRunner.forceConfirm

        val webAvailable = definitions.any { it.name == "search_web" || it.name == "fetch_url" }
        val effectiveSystemPrompt = request.systemPromptOverride ?: PromptBuilder.buildSystemPrompt(
            PromptConfig(
                isVoiceMode = request.isVoiceMode,
                planFirst = request.planFirst,
                webToolsAvailable = webAvailable,
                availableToolNames = definitions.map { it.name }.toSet(),
                memoryContext = request.memoryContext,
            ),
        )

        val startTimeMillis = System.currentTimeMillis()
        fun remainingTimeMillis(): Long? = effectiveTimeout?.let { it - (System.currentTimeMillis() - startTimeMillis) }

        suspend fun emitTimeoutSafely() {
            val timeoutLimit = effectiveTimeout ?: 0L
            val timeoutResult = if (completedToolSteps.isNotEmpty()) {
                ExecutionResult.partialSuccess(
                    message = "Execution timed out after ${timeoutLimit}ms. Completed steps: [${completedToolSteps.joinToString()}].",
                    userMessage = lastAssistantText.ifBlank {
                        "Operation timed out, but I completed: ${completedToolSteps.joinToString()}."
                    },
                    code = ErrorCode.TIMEOUT,
                    completedSteps = completedToolSteps,
                )
            } else {
                ExecutionResult.failure(
                    code = ErrorCode.TIMEOUT,
                    message = "Execution timed out after ${timeoutLimit}ms.",
                    userMessage = ExecutionErrorMapper.map(ErrorCode.TIMEOUT).message,
                )
            }
            emit(
                AgentEvent.Failed(
                    code = ErrorCode.TIMEOUT,
                    message = timeoutResult.message,
                    userFacingState = ExecutionErrorMapper.map(ErrorCode.TIMEOUT, timeoutResult.message),
                ),
            )
            emit(AgentEvent.FinalAnswer(timeoutResult.userMessage, timeoutResult))
        }

        try {
            var steps = 0

            while (steps < effectiveStepCap) {
                val remainingForTurn = remainingTimeMillis()
                if (remainingForTurn != null && remainingForTurn <= 0L) {
                    emitTimeoutSafely()
                    return@flow
                }

                steps++
                emit(AgentEvent.IterationStarted(steps))

                var assistantText = ""
                val requestedTools = mutableListOf<ChatStreamEvent.ToolCallRequested>()
                var streamError: ChatStreamEvent.Error? = null
                var toolSeen = false

                suspend fun handleStreamEvent(event: ChatStreamEvent) {
                    when (event) {
                        is ChatStreamEvent.TokenDelta -> {
                            assistantText += event.text
                            if (!toolSeen) emit(AgentEvent.TextDelta(event.text))
                        }
                        is ChatStreamEvent.ToolCallRequested -> {
                            requestedTools.add(event)
                            toolSeen = true
                        }
                        is ChatStreamEvent.Error -> streamError = event
                        is ChatStreamEvent.ReasoningDelta, is ChatStreamEvent.Usage, ChatStreamEvent.Done -> Unit
                    }
                }

                val effectiveHistory = contextManager.compactHistory(
                    baseHistory + turnLog,
                    historyTokenBudget = 3200,
                ).messages

                val chatRequest = ChatRequest(
                    conversationHistory = effectiveHistory,
                    systemPrompt = effectiveSystemPrompt,
                    model = request.modelId,
                    reasoningRequested = request.reasoningRequested,
                    toolsAvailable = if (supportsTools) definitions else null,
                )

                val streamOk = if (effectiveTimeout != null) {
                    val remainingMs = (remainingTimeMillis() ?: 1L).coerceAtLeast(1L)
                    withTimeoutOrNull(remainingMs) {
                        request.provider.streamChat(chatRequest).collect { handleStreamEvent(it) }
                        true
                    }
                } else {
                    request.provider.streamChat(chatRequest).collect { handleStreamEvent(it) }
                    true
                }

                if (streamOk == null) {
                    emitTimeoutSafely()
                    return@flow
                }

                lastAssistantText = assistantText
                logAgentResponse(steps, assistantText, requestedTools)

                if (streamError != null) {
                    val errCode = ErrorCode.fromString(streamError.code)
                    emit(
                        AgentEvent.Failed(
                            code = errCode,
                            message = streamError.message,
                            userFacingState = ExecutionErrorMapper.map(errCode, streamError.message),
                        ),
                    )
                    return@flow
                }

                if (requestedTools.isEmpty()) {
                    val derivedResponse = if (executionHistory.isNotEmpty()) {
                        val records = executionHistory.map {
                            com.jarvis.core.agent.execution.ToolExecutionRecord(
                                toolName = it.toolName,
                                success = it.success,
                                observationText = it.observationText,
                                rawArgs = it.normalizedArgs,
                            )
                        }
                        com.jarvis.core.agent.execution.ToolResultResponseDeriver.deriveFromHistory(records, lastToolFailed)
                    } else null

                    val finalExecutionResult = if (lastToolFailed != null) {
                        val (failedTool, errCode) = lastToolFailed
                        val failureUserMsg = assistantText.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                            ?: derivedResponse
                            ?: ExecutionErrorMapper.map(errCode, "Action on $failedTool failed").message
                        if (completedToolSteps.isNotEmpty()) {
                            ExecutionResult.partialSuccess(
                                message = "Partially completed: completed [${completedToolSteps.joinToString()}], but $failedTool failed with $errCode.",
                                userMessage = failureUserMsg,
                                code = errCode,
                                completedSteps = completedToolSteps,
                            )
                        } else {
                            ExecutionResult.failure(
                                code = errCode,
                                message = "Action on $failedTool failed: $errCode.",
                                userMessage = failureUserMsg,
                            )
                        }
                    } else {
                        val successUserMsg = assistantText.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                            ?: derivedResponse
                            ?: "No response was generated."
                        ExecutionResult.success(
                            message = successUserMsg,
                            userMessage = successUserMsg,
                            completedSteps = completedToolSteps,
                        )
                    }

                    val finalAnswerText = assistantText.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                        ?: derivedResponse
                        ?: finalExecutionResult.userMessage.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                        ?: finalExecutionResult.message.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                        ?: "No response was generated."

                    val sanitizedFinalAnswer = com.jarvis.core.agent.execution.ToolResultResponseDeriver.sanitizeArchitectureConcepts(finalAnswerText)
                    emit(AgentEvent.FinalAnswer(sanitizedFinalAnswer, finalExecutionResult))
                    return@flow
                }

                var abortTurn = false
                val conversationId = request.messages.firstOrNull()?.conversationId ?: "session_${System.currentTimeMillis()}"

                for (call in requestedTools) {
                    val remainingForCall = remainingTimeMillis()
                    if (remainingForCall != null && remainingForCall <= 0L) {
                        emitTimeoutSafely()
                        return@flow
                    }

                    val tool = registry.get(call.name)
                    val tier = tool?.tier ?: PermissionTier.READ_ONLY
                    val normalizedArgs = normalizeArgs(call.argsJson)
                    val isSideEffecting = (tier != PermissionTier.READ_ONLY)
                    val repetitionLimit = if (isSideEffecting) effectiveMaxSideEffectReps else effectiveMaxReadOnlyReps

                    val repeatedCount = countRepeatedExecutionsWithoutStateChange(
                        history = executionHistory,
                        toolName = call.name,
                        normalizedArgs = normalizedArgs,
                    )

                    if (repeatedCount >= repetitionLimit) {
                        val repetitionMsg = "Repetitive action detected for tool '${call.name}' with arguments '$normalizedArgs' repeated $repeatedCount times without meaningful state change."
                        val loopResult = if (completedToolSteps.isNotEmpty()) {
                            ExecutionResult.partialSuccess(
                                message = repetitionMsg,
                                userMessage = assistantText.ifBlank {
                                    "I stopped because '${call.name}' was repeating without making progress. Completed: [${completedToolSteps.joinToString()}]."
                                },
                                code = ErrorCode.LOOP_DETECTED,
                                completedSteps = completedToolSteps,
                            )
                        } else {
                            ExecutionResult.failure(
                                code = ErrorCode.LOOP_DETECTED,
                                message = repetitionMsg,
                                userMessage = assistantText.ifBlank {
                                    "I stopped because '${call.name}' was repeating without making progress."
                                },
                            )
                        }

                        emit(
                            AgentEvent.Failed(
                                code = ErrorCode.LOOP_DETECTED,
                                message = loopResult.message,
                                userFacingState = ExecutionErrorMapper.map(ErrorCode.LOOP_DETECTED, loopResult.message),
                            ),
                        )
                        emit(AgentEvent.FinalAnswer(loopResult.userMessage, loopResult))
                        return@flow
                    }

                    emit(AgentEvent.ToolRequested(call.name, call.argsJson, tier))

                    val idempotencyKey = request.agentRunId?.let { runId ->
                        "$runId:${call.name}:$normalizedArgs"
                    }

                    val outcome = if (effectiveTimeout != null) {
                        val remainingForTool = (remainingTimeMillis() ?: 1L).coerceAtLeast(1L)
                        withTimeoutOrNull(remainingForTool) {
                            toolExecutor.execute(
                                toolName = call.name,
                                argsJson = call.argsJson,
                                agentRunId = request.agentRunId,
                                idempotencyKey = idempotencyKey,
                                confirmationGate = effectiveConfirmationGate,
                                forceConfirm = effectiveForceConfirm,
                                disabledTools = disabledTools,
                                llmProvider = request.provider,
                                modelId = request.modelId,
                                onConfirmationRequired = { name, args ->
                                    emit(AgentEvent.ConfirmationRequired(name, args))
                                },
                            )
                        }
                    } else {
                        toolExecutor.execute(
                            toolName = call.name,
                            argsJson = call.argsJson,
                            agentRunId = request.agentRunId,
                            idempotencyKey = idempotencyKey,
                            confirmationGate = effectiveConfirmationGate,
                            forceConfirm = effectiveForceConfirm,
                            disabledTools = disabledTools,
                            llmProvider = request.provider,
                            modelId = request.modelId,
                            onConfirmationRequired = { name, args ->
                                emit(AgentEvent.ConfirmationRequired(name, args))
                            },
                        )
                    }

                    if (outcome == null) {
                        emitTimeoutSafely()
                        return@flow
                    }

                    if (outcome.cancelled) {
                        emit(AgentEvent.ToolCancelled(outcome.toolName))
                        emit(
                            AgentEvent.Cancelled(
                                reason = "Action '${outcome.toolName}' was cancelled by user",
                                userFacingState = ExecutionErrorMapper.map(ErrorCode.USER_CANCELLED),
                            ),
                        )
                        return@flow
                    }

                    if (outcome.rejected) {
                        executionHistory.add(
                            ToolExecutionHistory(
                                toolName = outcome.toolName,
                                normalizedArgs = normalizedArgs,
                                tier = tier,
                                isSideEffecting = isSideEffecting,
                                success = false,
                                observationText = outcome.rejectionReason ?: outcome.observationText,
                            ),
                        )
                        emit(
                            AgentEvent.ToolRejected(
                                name = outcome.toolName,
                                reason = outcome.rejectionReason ?: outcome.observationText,
                                errorCode = outcome.errorCode ?: ErrorCode.POLICY_DENIED,
                                userFacingState = outcome.userFacingState,
                            ),
                        )
                        val obs = outcome.observationText
                        recordToolTurn(call.name, call.argsJson, obs, call.id, turnLog, conversationId)
                        lastToolFailed = outcome.toolName to (outcome.errorCode ?: ErrorCode.POLICY_DENIED)
                        abortTurn = true
                        break
                    }

                    executionHistory.add(
                        ToolExecutionHistory(
                            toolName = outcome.toolName,
                            normalizedArgs = normalizedArgs,
                            tier = tier,
                            isSideEffecting = isSideEffecting,
                            success = outcome.success,
                            observationText = outcome.observationText,
                        ),
                    )

                    emit(AgentEvent.ToolExecuting(outcome.toolName))
                    if (outcome.success) {
                        completedToolSteps += outcome.toolName
                        lastToolFailed = null
                    } else {
                        lastToolFailed = outcome.toolName to (outcome.errorCode ?: ErrorCode.TOOL_EXECUTION_FAILED)
                    }

                    emit(
                        AgentEvent.ToolExecuted(
                            name = outcome.toolName,
                            success = outcome.success,
                            observationText = outcome.observationText,
                            errorCode = outcome.errorCode,
                            userFacingState = outcome.userFacingState,
                        ),
                    )

                    recordToolTurn(outcome.toolName, call.argsJson, outcome.observationText, call.id, turnLog, conversationId)
                }

                if (abortTurn) continue
            }

            emit(AgentEvent.StepCapReached(steps))
            val stepLimitResult = if (completedToolSteps.isNotEmpty()) {
                ExecutionResult.partialSuccess(
                    message = "Maximum step limit ($effectiveStepCap) reached. Completed: [${completedToolSteps.joinToString()}].",
                    userMessage = lastAssistantText.ifBlank {
                        "I reached the step limit of $effectiveStepCap, but completed: ${completedToolSteps.joinToString()}."
                    },
                    code = ErrorCode.STEP_LIMIT_REACHED,
                    completedSteps = completedToolSteps,
                )
            } else {
                ExecutionResult.failure(
                    code = ErrorCode.STEP_LIMIT_REACHED,
                    message = "Maximum step limit ($effectiveStepCap) reached without completion.",
                    userMessage = ExecutionErrorMapper.map(ErrorCode.STEP_LIMIT_REACHED).message,
                )
            }
            emit(
                AgentEvent.Failed(
                    code = ErrorCode.STEP_LIMIT_REACHED,
                    message = stepLimitResult.message,
                    userFacingState = ExecutionErrorMapper.map(ErrorCode.STEP_LIMIT_REACHED, stepLimitResult.message),
                ),
            )
            emit(AgentEvent.FinalAnswer(stepLimitResult.userMessage, stepLimitResult))
        } catch (e: CancellationException) {
            if (currentCoroutineContext()[Job]?.isActive == true) {
                emit(
                    AgentEvent.Cancelled(
                        reason = "Execution cancelled",
                        userFacingState = ExecutionErrorMapper.map(ErrorCode.USER_CANCELLED),
                    ),
                )
            }
            throw e
        } catch (t: Throwable) {
            val errCode = ErrorCode.UNKNOWN_ERROR
            val msg = t.message ?: "An unexpected error occurred during execution"
            emit(
                AgentEvent.Failed(
                    code = errCode,
                    message = msg,
                    userFacingState = ExecutionErrorMapper.map(errCode, msg),
                ),
            )
        }
    }

    private data class ToolExecutionHistory(
        val toolName: String,
        val normalizedArgs: String,
        val tier: PermissionTier,
        val isSideEffecting: Boolean,
        val success: Boolean,
        val observationText: String,
    )

    private fun countRepeatedExecutionsWithoutStateChange(
        history: List<ToolExecutionHistory>,
        toolName: String,
        normalizedArgs: String,
    ): Int {
        var count = 0
        var lastObservation: String? = null

        for (i in history.indices.reversed()) {
            val entry = history[i]
            if (entry.toolName == toolName && entry.normalizedArgs == normalizedArgs) {
                if (lastObservation == null) {
                    lastObservation = entry.observationText
                    count++
                } else if (entry.observationText == lastObservation) {
                    count++
                } else {
                    // Observation was different: meaningful state change observed
                    break
                }
            } else if (entry.isSideEffecting && entry.success) {
                // An intervening successful side effect occurred, changing state
                break
            }
        }
        return count
    }

    private fun normalizeArgs(rawJson: String?): String {
        if (rawJson.isNullOrBlank()) return "{}"
        val trimmed = rawJson.trim()
        return runCatching {
            canonicalizeJsonElement(Json.parseToJsonElement(trimmed)).toString()
        }.getOrElse {
            trimmed.replace(Regex("\\s+"), " ")
        }
    }

    private fun logAgentResponse(
        steps: Int,
        assistantText: String,
        requestedTools: List<ChatStreamEvent.ToolCallRequested>,
    ) {
        if (!isAndroidRuntime) return
        Log.i(
            TAG,
            "AGENT RESPONSE: step=$steps text=${assistantText.take(MAX_DIAGNOSTIC_TEXT).replace("\n", " ")} " +
                "toolCalls=${requestedTools.joinToString(prefix = "[", postfix = "]") { "${it.name}(${AuditRedaction.redact(it.argsJson)})" }}",
        )
    }

    private fun recordToolTurn(
        toolName: String,
        argsJson: String,
        observation: String,
        callId: String?,
        turnLog: MutableList<Message>,
        conversationId: String,
    ) {
        val effectiveCallId = callId ?: "call_${toolName}_${System.currentTimeMillis()}"
        turnLog += Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallId = effectiveCallId,
            toolCallName = toolName,
            toolCallArgsJson = argsJson,
        )
        turnLog += Message(
            conversationId = conversationId,
            role = MessageRole.TOOL,
            content = observation,
            toolCallId = effectiveCallId,
            toolCallName = toolName,
        )
    }

    companion object {
        const val DEFAULT_STEP_CAP = 10
        const val MAX_STEP_CAP = 40
        const val DEFAULT_MAX_SIDE_EFFECT_REPETITIONS = 2
        const val DEFAULT_MAX_READ_ONLY_REPETITIONS = 3
        private const val TAG = "AgentRunner"
        private const val MAX_DIAGNOSTIC_TEXT = 120

        private val isAndroidRuntime: Boolean by lazy {
            try {
                Class.forName("android.os.Build")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }
    }
}
