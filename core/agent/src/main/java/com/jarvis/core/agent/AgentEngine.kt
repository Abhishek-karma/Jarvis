package com.jarvis.core.agent

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
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
)

/**
 * Answers a user request through a step-capped ReAct loop (Thought → Action → Observation).
 * The caller — later, the Agent Canvas integration — supplies the
 * [ConfirmationGate]; the engine itself only ever asks, it never auto-allows a Sensitive
 * tool, and every executed or cancelled call is written to the [AuditLogger].
 *
 * Cancellation contract: a tool call already in flight is allowed to finish
 * before the loop halts, so nothing is killed mid-write. Cancelling the run aborts the
 * provider stream (each adapter closes its socket in awaitClose).
 */
class AgentEngine(
    private val registry: ToolRegistry,
    private val audit: AuditLogger,
    private val confirmationGate: ConfirmationGate,
    private val stepCap: Int = DEFAULT_STEP_CAP,
    /** Cautious mode: every tool call requires confirmation regardless of tier. */
    private val forceConfirm: Boolean = false,
) {
    private val validator = ToolArgsValidator()

    init {
        require(stepCap in 1..MAX_STEP_CAP) { "stepCap must be within 1..$MAX_STEP_CAP" }
    }

    fun run(request: AgentRunRequest): Flow<AgentEvent> =
        flow {
            emit(AgentEvent.RunStarted)
            val definitions = registry.definitions()
            val supportsTools = request.provider.capabilities.supportsTools && definitions.isNotEmpty()
            val baseHistory = request.messages.filterNot { it.role == MessageRole.TOOL }
            val turnLog = mutableListOf<Message>() // assistant + tool observations appended per iteration

            var steps = 0
            while (steps < stepCap) {
                steps++
                emit(AgentEvent.IterationStarted(steps))

                val streamEvents =
                    request.provider
                        .streamChat(
                            ChatRequest(
                                conversationHistory = baseHistory + turnLog,
                                systemPrompt = SYSTEM_PROMPT,
                                model = request.modelId,
                                reasoningRequested = request.reasoningRequested,
                                toolsAvailable = if (supportsTools) definitions else null,
                            ),
                        ).toList()

                var assistantText = ""
                var requestedTool: ChatStreamEvent.ToolCallRequested? = null
                var streamError: ChatStreamEvent.Error? = null
                for (event in streamEvents) {
                    when (event) {
                        is ChatStreamEvent.TokenDelta -> assistantText += event.text
                        is ChatStreamEvent.ToolCallRequested -> if (requestedTool == null) requestedTool = event
                        is ChatStreamEvent.Error -> streamError = event
                        is ChatStreamEvent.ReasoningDelta, is ChatStreamEvent.Usage, ChatStreamEvent.Done -> Unit
                    }
                }

                if (streamError != null) {
                    emit(AgentEvent.Failed(streamError.code, streamError.message))
                    return@flow
                }

                val call = requestedTool
                if (call == null) {
                    emit(AgentEvent.FinalAnswer(assistantText))
                    return@flow
                }

                val tool = registry.get(call.name)
                if (tool == null) {
                    emit(
                        AgentEvent.ToolRejected(
                            call.name,
                            "Unknown tool. Available tools: ${definitions.joinToString { it.name }}.",
                        ),
                    )
                    turnLog += assistantMessage(assistantText)
                    turnLog +=
                        userMessage(
                            "Unknown tool \"${call.name}\". Available tools: ${definitions.joinToString { it.name }}.",
                        )
                    continue
                }

                emit(AgentEvent.ToolRequested(tool.name, call.argsJson, tool.tier))

                when (val validation = validator.validate(tool.parametersSchemaJson, call.argsJson)) {
                    is ToolArgsValidator.Result.Rejected -> {
                        emit(AgentEvent.ToolRejected(tool.name, validation.reason))
                        turnLog += assistantMessage(assistantText)
                        turnLog +=
                            userMessage(
                                "Tool \"${tool.name}\" rejected its arguments: ${validation.reason} Fix the arguments and retry.",
                            )
                        continue
                    }
                    ToolArgsValidator.Result.Valid -> Unit
                }

                val userConfirmed: Boolean
                if (forceConfirm || tool.tier == PermissionTier.SENSITIVE) {
                    emit(AgentEvent.ConfirmationRequired(tool.name, call.argsJson))
                    if (!confirmationGate.confirm(tool.name, call.argsJson)) {
                        audit.record(cancelledRecord(request, tool, call))
                        emit(AgentEvent.ToolCancelled(tool.name))
                        return@flow
                    }
                    userConfirmed = true
                } else {
                    userConfirmed = false
                }

                emit(AgentEvent.ToolExecuting(tool.name))
                // NonCancellable: an in-flight call finishes (and its audit row is written) even
                // if the run is cancelled — the loop is what halts, not the tool mid-write.
                val result =
                    withContext(NonCancellable) {
                        val outcome = tool.execute(call.argsJson)
                        audit.record(
                            AuditRecord(
                                agentRunId = request.agentRunId,
                                toolName = tool.name,
                                tier = tool.tier.wireName,
                                paramsRedactedJson = AuditRedaction.redact(call.argsJson),
                                resultStatus = if (outcome.success) "success" else "failure",
                                userConfirmed = userConfirmed,
                            ),
                        )
                        outcome
                    }
                emit(AgentEvent.ToolExecuted(tool.name, result.success, result.observationText))

                // The executed call becomes a paired assistant(tool_calls) + tool(observation)
                // turn under one synthesized id, which the adapter echoes per dialect.
                val callId = UUID.randomUUID().toString()
                turnLog +=
                    assistantMessage(
                        text = assistantText,
                        toolCallId = callId,
                        toolCallName = tool.name,
                        toolCallArgsJson = call.argsJson,
                    )
                turnLog +=
                    toolMessage(
                        observation =
                            if (result.success) {
                                result.observationText
                            } else {
                                "Tool \"${tool.name}\" failed: ${result.error ?: result.observationText}"
                            },
                        toolCallId = callId,
                        toolCallName = tool.name,
                    )
            }
            emit(AgentEvent.StepCapReached(steps))
        }

    private fun cancelledRecord(
        request: AgentRunRequest,
        tool: Tool,
        call: ChatStreamEvent.ToolCallRequested,
    ) = AuditRecord(
        agentRunId = request.agentRunId,
        toolName = tool.name,
        tier = tool.tier.wireName,
        paramsRedactedJson = AuditRedaction.redact(call.argsJson),
        resultStatus = "cancelled",
        userConfirmed = false,
    )

    private fun assistantMessage(
        text: String,
        toolCallId: String? = null,
        toolCallName: String? = null,
        toolCallArgsJson: String? = null,
    ) = Message(
        conversationId = "",
        role = MessageRole.ASSISTANT,
        content = text,
        toolCallId = toolCallId,
        toolCallName = toolCallName,
        toolCallArgsJson = toolCallArgsJson,
    )

    private fun toolMessage(
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

    /** Provider-dialect-neutral feedback channel for turns that were never executed (rejections, unknown tools). */
    private fun userMessage(note: String) =
        Message(
            conversationId = "",
            role = MessageRole.USER,
            content = note,
        )

    companion object {
        const val DEFAULT_STEP_CAP = 15
        const val MAX_STEP_CAP = 40 // hard ceiling against runaway loops
        const val SYSTEM_PROMPT =
            "You are Jarvis's agent. Use the provided tools when they help " +
                "fulfill the user's request: request exactly one tool call per turn, wait for the " +
                "Observation, and keep going until the task is done, then answer the user directly. " +
                "Never invent a tool result — only report what an Observation actually says."
    }
}

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
