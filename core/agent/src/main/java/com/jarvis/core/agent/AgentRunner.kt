package com.jarvis.core.agent

import com.jarvis.core.agent.prompt.PromptBuilder
import com.jarvis.core.agent.prompt.PromptConfig
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

/** Input to one agent run: the provider/model and the message history ending with the user's request. */
data class AgentRunRequest(
    val provider: LlmProvider,
    val modelId: String,
    val messages: List<Message>,
    val agentRunId: String? = null,
    /** Derived reasoning flag — the last user turn's ThinkModeHeuristic decision. */
    val reasoningRequested: Boolean = false,
    /** Injected memory facts/preferences (filtered by privacy rules). */
    val memoryContext: String? = null,
    /** Whether plan-first mode is active (agent formulates an explicit plan before acting). */
    val planFirst: Boolean = false,
    /** Whether voice mode is active (agent formulates concise, speakable answers). */
    val isVoiceMode: Boolean = false,
    /** Whether running an on-device local model. */
    val isLocal: Boolean = false,
    /** Explicit system prompt override if provided by caller. */
    val systemPromptOverride: String? = null,
)

/** Answers whether a pending Sensitive-tier call may proceed. */
fun interface ConfirmationGate {
    suspend fun confirm(
        toolName: String,
        argsJson: String,
    ): Boolean
}

/** Streamed progress of an agent run — the surface the Agent Canvas will render. */
sealed class AgentEvent {
    data object RunStarted : AgentEvent()

    data class IterationStarted(
        val step: Int,
    ) : AgentEvent()

    data class ToolRequested(
        val name: String,
        val argsJson: String,
        val tier: PermissionTier,
    ) : AgentEvent()

    data class ConfirmationRequired(
        val name: String,
        val argsJson: String,
    ) : AgentEvent()

    data class ToolExecuting(
        val name: String,
    ) : AgentEvent()

    data class ToolExecuted(
        val name: String,
        val success: Boolean,
        val observationText: String,
    ) : AgentEvent()

    data class ToolRejected(
        val name: String,
        val reason: String,
    ) : AgentEvent()

    data class ToolCancelled(
        val name: String,
    ) : AgentEvent()

    data class FinalAnswer(
        val text: String,
    ) : AgentEvent()

    data class Failed(
        val code: String,
        val message: String,
    ) : AgentEvent()

    data class StepCapReached(
        val stepsUsed: Int,
    ) : AgentEvent()
}

/**
 * Clean AgentRunner owning the single primary execution loop:
 *
 * USER REQUEST
 *     ↓
 *   MODEL
 *     ↓
 * TOOL CALL(S)
 *     ↓
 *  VALIDATE
 *     ↓
 *   POLICY
 *     ↓
 *  EXECUTE (Bounded parallel read, sequential action/sensitive)
 *     ↓
 * OBSERVATION
 *     ↓
 *   MODEL
 *     ↓
 * FINAL ANSWER
 *
 * Concurrency & Cancellation rules:
 * - Read-only tools execute bounded in parallel up to MAX_PARALLEL_READS.
 * - Action & Sensitive tools execute sequentially.
 * - Tool execution runs under the active caller coroutine context without blanket NonCancellable,
 *   so cooperative cancellations terminate promptly. NonCancellable is strictly reserved for
 *   persisting audit records or final cleanup.
 */
class AgentRunner(
    private val registry: ToolRegistry,
    private val audit: AuditLogger,
    private val confirmationGate: ConfirmationGate,
    private val toolPolicy: ToolPolicy = DefaultToolPolicy(),
    private val stepCap: Int = DEFAULT_STEP_CAP,
    private val forceConfirm: Boolean = false,
    private val parallelReadLimit: Int = DEFAULT_PARALLEL_READ_LIMIT,
    private val disabledTools: Set<String> = emptySet(),
    private val contextManager: ContextManager = ContextManager(),
) {
    private val validator = ToolArgsValidator()

    init {
        require(stepCap in 1..MAX_STEP_CAP) { "stepCap must be within 1..$MAX_STEP_CAP" }
    }

    fun run(request: AgentRunRequest): Flow<AgentEvent> = flow {
        emit(AgentEvent.RunStarted)
        val definitions = registry.definitions().filterNot { it.name in disabledTools }
        val supportsTools = request.provider.capabilities.supportsTools && definitions.isNotEmpty()
        val baseHistory = request.messages.filterNot { it.role == MessageRole.TOOL }
        val turnLog = mutableListOf<Message>()

        val webAvailable = definitions.any { it.name == "search_web" || it.name == "fetch_url" || it.name == "web_search" }
        val effectiveSystemPrompt = request.systemPromptOverride ?: PromptBuilder.buildSystemPrompt(
            PromptConfig(
                isLocal = request.isLocal || !supportsTools,
                isVoiceMode = request.isVoiceMode,
                planFirst = request.planFirst,
                webToolsAvailable = webAvailable,
                availableToolNames = definitions.map { it.name }.toSet(),
                memoryContext = request.memoryContext,
            ),
        )

        var steps = 0
        while (steps < stepCap) {
            steps++
            emit(AgentEvent.IterationStarted(steps))

            val historyBudget = if (request.isLocal) 1500 else 3200
            val effectiveHistory = contextManager.compactHistory(
                baseHistory + turnLog,
                historyTokenBudget = historyBudget,
            ).messages

            val streamEvents = request.provider
                .streamChat(
                    ChatRequest(
                        conversationHistory = effectiveHistory,
                        systemPrompt = effectiveSystemPrompt,
                        model = request.modelId,
                        reasoningRequested = request.reasoningRequested,
                        toolsAvailable = if (supportsTools) definitions else null,
                    ),
                ).toList()

            var assistantText = ""
            val requestedTools = mutableListOf<ChatStreamEvent.ToolCallRequested>()
            var streamError: ChatStreamEvent.Error? = null
            for (event in streamEvents) {
                when (event) {
                    is ChatStreamEvent.TokenDelta -> assistantText += event.text
                    is ChatStreamEvent.ToolCallRequested -> requestedTools.add(event)
                    is ChatStreamEvent.Error -> streamError = event
                    is ChatStreamEvent.ReasoningDelta, is ChatStreamEvent.Usage, ChatStreamEvent.Done -> Unit
                }
            }

            if (streamError != null) {
                emit(AgentEvent.Failed(streamError.code, streamError.message))
                return@flow
            }

            if (requestedTools.isEmpty()) {
                emit(AgentEvent.FinalAnswer(assistantText))
                return@flow
            }

            var hasRejection = false
            val validCalls = mutableListOf<Pair<ChatStreamEvent.ToolCallRequested, Tool>>()
            for (call in requestedTools) {
                val tool = registry.get(call.name)
                if (tool == null || call.name in disabledTools) {
                    emit(
                        AgentEvent.ToolRejected(
                            call.name,
                            "Unknown tool. Available tools: ${definitions.joinToString { it.name }}.",
                        ),
                    )
                    turnLog += assistantMessage(assistantText)
                    turnLog += userMessage(
                        "Unknown tool \"${call.name}\". Available tools: ${definitions.joinToString { it.name }}.",
                    )
                    hasRejection = true
                    break
                }

                emit(AgentEvent.ToolRequested(tool.name, call.argsJson, tool.tier))

                when (val validation = validator.validate(tool.parametersSchemaJson, call.argsJson)) {
                    is ToolArgsValidator.Result.Rejected -> {
                        emit(AgentEvent.ToolRejected(tool.name, validation.reason))
                        turnLog += assistantMessage(assistantText)
                        turnLog += userMessage(
                            "Tool \"${tool.name}\" rejected its arguments: ${validation.reason} Fix the arguments and retry.",
                        )
                        hasRejection = true
                        break
                    }
                    ToolArgsValidator.Result.Valid -> {
                        validCalls.add(call to tool)
                    }
                }
            }

            if (hasRejection) {
                continue
            }

            val allReadOnly = validCalls.all { it.second.tier == PermissionTier.READ_ONLY && !forceConfirm }
            if (allReadOnly && validCalls.size > 1) {
                for ((_, tool) in validCalls) {
                    emit(AgentEvent.ToolExecuting(tool.name))
                }

                val semaphore = Semaphore(parallelReadLimit)
                val executedResults = coroutineScope {
                    validCalls.map { (call, tool) ->
                        async {
                            val r = try {
                                semaphore.withPermit {
                                    tool.execute(call.argsJson)
                                }
                            } catch (e: CancellationException) {
                                withContext(NonCancellable) {
                                    audit.record(
                                        AuditRecord(
                                            agentRunId = request.agentRunId,
                                            toolName = tool.name,
                                            tier = tool.tier.name.lowercase(),
                                            paramsRedactedJson = AuditRedaction.redact(call.argsJson),
                                            resultStatus = "cancelled",
                                            userConfirmed = false,
                                        ),
                                    )
                                }
                                throw e
                            }
                            val clampedObs = contextManager.clampObservation(
                                r.observationText,
                                maxTokens = if (request.isLocal) 400 else 1000,
                            )
                            withContext(NonCancellable) {
                                audit.record(
                                    AuditRecord(
                                        agentRunId = request.agentRunId,
                                        toolName = tool.name,
                                        tier = tool.tier.name.lowercase(),
                                        paramsRedactedJson = AuditRedaction.redact(call.argsJson),
                                        resultStatus = if (r.success) "success" else "failed",
                                        userConfirmed = false,
                                    ),
                                )
                            }
                            Triple(call, tool, r.copy(observationText = clampedObs))
                        }
                    }.awaitAll()
                }

                for ((call, tool, r) in executedResults) {
                    val obs = r.observationText
                    emit(AgentEvent.ToolExecuted(tool.name, r.success, obs))
                    val callId = UUID.randomUUID().toString()
                    turnLog += assistantToolCallMessage(
                        toolCallId = callId,
                        toolCallName = tool.name,
                        toolCallArgsJson = call.argsJson,
                    )
                    turnLog += toolResultMessage(
                        observation = obs,
                        toolCallId = callId,
                        toolCallName = tool.name,
                    )
                }
            } else {
                for ((call, tool) in validCalls) {
                    when (val decision = toolPolicy.evaluate(tool, call.argsJson, forceConfirm)) {
                        is PolicyDecision.Deny -> {
                            emit(AgentEvent.ToolRejected(tool.name, decision.reason))
                            turnLog += assistantMessage(assistantText)
                            turnLog += userMessage(
                                "Execution blocked by policy for \"${tool.name}\": ${decision.reason}",
                            )
                            withContext(NonCancellable) {
                                audit.record(
                                    AuditRecord(
                                        agentRunId = request.agentRunId,
                                        toolName = tool.name,
                                        tier = tool.tier.name.lowercase(),
                                        paramsRedactedJson = AuditRedaction.redact(call.argsJson),
                                        resultStatus = "blocked",
                                        userConfirmed = false,
                                    ),
                                )
                            }
                            continue
                        }
                        is PolicyDecision.RequireConfirmation -> {
                            emit(AgentEvent.ConfirmationRequired(tool.name, call.argsJson))
                            val allowed = confirmationGate.confirm(tool.name, call.argsJson)
                            if (!allowed) {
                                emit(AgentEvent.ToolCancelled(tool.name))
                                withContext(NonCancellable) {
                                    audit.record(
                                        AuditRecord(
                                            agentRunId = request.agentRunId,
                                            toolName = tool.name,
                                            tier = tool.tier.name.lowercase(),
                                            paramsRedactedJson = AuditRedaction.redact(call.argsJson),
                                            resultStatus = "cancelled",
                                            userConfirmed = false,
                                        ),
                                    )
                                }
                                return@flow
                            }
                        }
                        PolicyDecision.Allow -> {
                            // Proceed directly
                        }
                    }

                    emit(AgentEvent.ToolExecuting(tool.name))
                    val result = try {
                        tool.execute(call.argsJson)
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) {
                            audit.record(
                                AuditRecord(
                                    agentRunId = request.agentRunId,
                                    toolName = tool.name,
                                    tier = tool.tier.name.lowercase(),
                                    paramsRedactedJson = AuditRedaction.redact(call.argsJson),
                                    resultStatus = "cancelled",
                                    userConfirmed = false,
                                ),
                            )
                        }
                        throw e
                    }
                    val clampedObs = contextManager.clampObservation(
                        result.observationText,
                        maxTokens = if (request.isLocal) 400 else 1000,
                    )
                    withContext(NonCancellable) {
                        audit.record(
                            AuditRecord(
                                agentRunId = request.agentRunId,
                                toolName = tool.name,
                                tier = tool.tier.name.lowercase(),
                                paramsRedactedJson = AuditRedaction.redact(call.argsJson),
                                resultStatus = if (result.success) "success" else "failed",
                                userConfirmed = tool.tier == PermissionTier.SENSITIVE || forceConfirm,
                            ),
                        )
                    }

                    emit(AgentEvent.ToolExecuted(tool.name, result.success, clampedObs))
                    val callId = UUID.randomUUID().toString()
                    turnLog += assistantToolCallMessage(
                        toolCallId = callId,
                        toolCallName = tool.name,
                        toolCallArgsJson = call.argsJson,
                    )
                    turnLog += toolResultMessage(
                        observation = clampedObs,
                        toolCallId = callId,
                        toolCallName = tool.name,
                    )
                }
            }
        }

        emit(AgentEvent.StepCapReached(steps))
    }

    private fun assistantMessage(text: String) =
        Message(
            conversationId = "",
            role = MessageRole.ASSISTANT,
            content = text,
        )

    private fun assistantToolCallMessage(
        toolCallId: String,
        toolCallName: String,
        toolCallArgsJson: String,
    ) = Message(
        conversationId = "",
        role = MessageRole.ASSISTANT,
        content = "",
        toolCallId = toolCallId,
        toolCallName = toolCallName,
        toolCallArgsJson = toolCallArgsJson,
    )

    private fun toolResultMessage(
        observation: String,
        toolCallId: String,
        toolCallName: String,
    ) = Message(
        conversationId = "",
        role = MessageRole.TOOL,
        content = observation,
        toolCallId = toolCallId,
        toolCallName = toolCallName,
    )

    private fun userMessage(note: String) =
        Message(
            conversationId = "",
            role = MessageRole.USER,
            content = note,
        )

    companion object {
        const val DEFAULT_STEP_CAP = 15
        const val MAX_STEP_CAP = 40
        const val DEFAULT_PARALLEL_READ_LIMIT = 4
        val SYSTEM_PROMPT = PromptBuilder.buildCloudSystemPrompt(
            PromptConfig(
                webToolsAvailable = true,
                isVoiceMode = false,
                planFirst = false,
            ),
        )
    }
}
