package com.jarvis.core.agent

import com.jarvis.core.agent.prompt.PromptBuilder
import com.jarvis.core.agent.prompt.PromptConfig
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import com.jarvis.core.network.ToolResponsePayload
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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

    /** A chunk of the final answer text, streamed as the model generates it. */
    data class TextDelta(
        val text: String,
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
 * - Tools execute sequentially in the exact order requested by the model.
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

        val session = if (supportsTools && request.provider.capabilities.supportsSessions) {
            request.provider.startSession(
                ChatRequest(
                    conversationHistory = baseHistory,
                    systemPrompt = effectiveSystemPrompt,
                    model = request.modelId,
                    reasoningRequested = request.reasoningRequested,
                    toolsAvailable = definitions,
                ),
            )
        } else null

        try {
            var steps = 0
            var pendingToolResponses: List<ToolResponsePayload>? = null

            while (steps < stepCap) {
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
                            // Stream text to the UI live, unless a tool call shares this turn.
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

                if (session != null) {
                    if (steps == 1) {
                        session.sendInitial().collect { handleStreamEvent(it) }
                    } else {
                        val responses = pendingToolResponses.orEmpty()
                        pendingToolResponses = null
                        session.sendToolResponses(responses).collect { handleStreamEvent(it) }
                    }
                } else {
                    val historyBudget = 3200
                    val effectiveHistory = contextManager.compactHistory(
                        baseHistory + turnLog,
                        historyTokenBudget = historyBudget,
                    ).messages

                    request.provider
                        .streamChat(
                            ChatRequest(
                                conversationHistory = effectiveHistory,
                                systemPrompt = effectiveSystemPrompt,
                                model = request.modelId,
                                reasoningRequested = request.reasoningRequested,
                                toolsAvailable = if (supportsTools) definitions else null,
                            ),
                        ).collect { handleStreamEvent(it) }
                }
                // Diagnostic (logcat only, never UI): what the agent layer received after the
                // provider converted the model response into structured events. Compared against
                // LiteRtLmEngine's "LOCAL MODEL RESPONSE" log this pinpoints which layer drops
                // native tool calls (Case A) or shows both empty (Case B — model never produced them).
                logAgentResponse(steps, assistantText, requestedTools)

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
                    if (tool == null) {
                        emit(
                            AgentEvent.ToolRejected(
                                call.name,
                                "Unknown tool. Available tools: ${definitions.joinToString { it.name }}.",
                            ),
                        )
                        // Close the tool-call turn with a tool-role response so the model's
                        // tool call is properly paired in the conversation protocol.
                        val obs = "Unknown tool \"${call.name}\". Available tools: ${definitions.joinToString { it.name }}."
                        pendingToolResponses = listOf(
                            recordToolTurn(
                                toolName = call.name,
                                argsJson = call.argsJson,
                                observation = obs,
                                turnLog = turnLog,
                            ),
                        )
                        hasRejection = true
                        break
                    }

                    // Use canonical tool.name for disabled-check, not caller-provided call.name.
                    // ToolRegistry aliases can map webSearch → search_web; disabledTools are indexed
                    // by canonical name, so checking tool.name ensures the alias bypass is prevented.
                    if (tool.name in disabledTools) {
                        emit(
                            AgentEvent.ToolRejected(
                                tool.name,
                                "Tool '${tool.name}' is disabled in this environment.",
                            ),
                        )
                        val obs = "Tool \"${tool.name}\" is disabled in this environment."
                        pendingToolResponses = listOf(
                            recordToolTurn(
                                toolName = tool.name,
                                argsJson = call.argsJson,
                                observation = obs,
                                turnLog = turnLog,
                            ),
                        )
                        hasRejection = true
                        break
                    }

                    emit(AgentEvent.ToolRequested(tool.name, call.argsJson, tool.tier))

                    when (val validation = validator.validate(tool.parametersSchemaJson, call.argsJson)) {
                        is ToolArgsValidator.Result.Rejected -> {
                            emit(AgentEvent.ToolRejected(tool.name, validation.reason))
                            // Close the tool-call turn with a tool-role response so the model
                            // can retry with corrected arguments in the next turn.
                            val obs = "Tool \"${tool.name}\" rejected its arguments: ${validation.reason} Fix the arguments and retry."
                            pendingToolResponses = listOf(
                                recordToolTurn(
                                    toolName = tool.name,
                                    argsJson = call.argsJson,
                                    observation = obs,
                                    turnLog = turnLog,
                                ),
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

                // Evaluate policy once per tool call
                val policyDecisions = validCalls.map { (call, tool) ->
                    toolPolicy.evaluate(tool, call.argsJson, forceConfirm)
                }

                // If any tool call is denied by policy, reject and retry the turn.
                var policyDenied = false
                for (i in validCalls.indices) {
                    val (call, tool) = validCalls[i]
                    val decision = policyDecisions[i]
                    if (decision is PolicyDecision.Deny) {
                        emit(AgentEvent.ToolRejected(tool.name, decision.reason))
                        val obs = "Execution blocked by policy for \"${tool.name}\": ${decision.reason}"
                        pendingToolResponses = listOf(
                            recordToolTurn(
                                toolName = tool.name,
                                argsJson = call.argsJson,
                                observation = obs,
                                turnLog = turnLog,
                            ),
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
                        policyDenied = true
                        break
                    }
                }
                if (policyDenied) {
                    continue
                }

                val toolResponses = mutableListOf<ToolResponsePayload>()
                for (i in validCalls.indices) {
                    val (call, tool) = validCalls[i]
                    val decision = policyDecisions[i]

                    if (decision is PolicyDecision.RequireConfirmation) {
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

                    emit(AgentEvent.ToolExecuting(tool.name))
                    val r = executeAndAuditTool(
                        tool = tool,
                        argsJson = call.argsJson,
                        agentRunId = request.agentRunId,
                        userConfirmed = tool.tier == PermissionTier.SENSITIVE || forceConfirm,
                    )

                    val obs = r.observationText
                    emit(AgentEvent.ToolExecuted(tool.name, r.success, obs))
                    toolResponses += recordToolTurn(
                        toolName = tool.name,
                        argsJson = call.argsJson,
                        observation = obs,
                        turnLog = turnLog,
                    )
                }
                pendingToolResponses = toolResponses
            }

            emit(AgentEvent.StepCapReached(steps))
        } finally {
            session?.close()
        }
    }

    /**
     * Logs the AGENT RESPONSE diagnostic. Reflection-free runtime check keeps JVM unit tests
     * working (android.util.Log is not mocked there) while staying active on-device.
     */
    private fun logAgentResponse(
        steps: Int,
        assistantText: String,
        requestedTools: List<ChatStreamEvent.ToolCallRequested>,
    ) {
        if (!isAndroidRuntime) return
        Log.i(
            TAG,
            "AGENT RESPONSE: step=$steps text=${assistantText.take(MAX_DIAGNOSTIC_TEXT).replace("\n", " ")} " +
                "toolCalls=${requestedTools.joinToString(prefix = "[", postfix = "]") { "${it.name}(${it.argsJson})" }}",
        )
    }

    private suspend fun executeAndAuditTool(
        tool: Tool,
        argsJson: String,
        agentRunId: String?,
        userConfirmed: Boolean,
    ): ToolResult {
        val result = try {
            tool.execute(argsJson)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                audit.record(
                    AuditRecord(
                        agentRunId = agentRunId,
                        toolName = tool.name,
                        tier = tool.tier.name.lowercase(),
                        paramsRedactedJson = AuditRedaction.redact(argsJson),
                        resultStatus = "cancelled",
                        userConfirmed = userConfirmed,
                    ),
                )
            }
            throw e
        } catch (e: Exception) {
            ToolResult(
                success = false,
                observationText = "Tool execution failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        val clampedObs = contextManager.clampObservation(
            result.observationText,
            maxTokens = 1000,
        )

        withContext(NonCancellable) {
            audit.record(
                AuditRecord(
                    agentRunId = agentRunId,
                    toolName = tool.name,
                    tier = tool.tier.name.lowercase(),
                    paramsRedactedJson = AuditRedaction.redact(argsJson),
                    resultStatus = if (result.success) "success" else "failed",
                    userConfirmed = userConfirmed,
                ),
            )
        }

        return result.copy(observationText = clampedObs)
    }

    private fun recordToolTurn(
        toolName: String,
        argsJson: String,
        observation: String,
        turnLog: MutableList<Message>,
        toolCallId: String = UUID.randomUUID().toString(),
    ): ToolResponsePayload {
        turnLog += assistantToolCallMessage(
            toolCallId = toolCallId,
            toolCallName = toolName,
            toolCallArgsJson = argsJson,
        )
        turnLog += toolResultMessage(
            observation = observation,
            toolCallId = toolCallId,
            toolCallName = toolName,
        )
        return ToolResponsePayload(
            toolName = toolName,
            observation = observation,
            toolCallId = toolCallId,
        )
    }

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

    companion object {
        private const val TAG = "AgentRunner"

        /** Caps the diagnostic text dump so logcat lines stay readable. */
        private const val MAX_DIAGNOSTIC_TEXT = 600

        /** True only on an Android runtime — android.util.Log is unmockable in JVM unit tests. */
        private val isAndroidRuntime = "android.os.Log".let { className ->
            runCatching { Class.forName(className) }.isSuccess
        }

        const val DEFAULT_STEP_CAP = 15
        const val MAX_STEP_CAP = 40
        val SYSTEM_PROMPT = PromptBuilder.buildCloudSystemPrompt(
            PromptConfig(
                webToolsAvailable = true,
                isVoiceMode = false,
                planFirst = false,
            ),
        )
    }
}
