package com.jarvis.core.agent

import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.agent.execution.ExecutionResult
import com.jarvis.core.agent.needle.EscalationReason
import com.jarvis.core.agent.needle.NeedleRouter
import com.jarvis.core.agent.needle.RoutingDecision
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.repository.TaskRepository
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level goal submission to the Goal Engine.
 */
data class AssistantGoal(
    val id: String = UUID.randomUUID().toString(),
    val goalDescription: String,
    val source: String = "chat",
    val conversationId: String? = null,
    val messages: List<Message> = emptyList(),
    val provider: LlmProvider,
    val modelId: String,
    val reasoningRequested: Boolean = false,
    val memoryContext: String? = null,
    val planFirst: Boolean = false,
    val isVoiceMode: Boolean = false,
    val confirmationGate: ConfirmationGate? = null,
    val forceConfirm: Boolean = false,
)

/**
 * Plan formulated for achieving the user's goal.
 */
data class AssistantPlan(
    val goalId: String,
    val explanation: String,
    val targetToolCategories: List<String> = emptyList(),
)

sealed class GoalEvent {
    data class StatusChanged(val message: String) : GoalEvent()
    data class PlanGenerated(val plan: AssistantPlan) : GoalEvent()
    data class MilestoneReached(val description: String, val stepIndex: Int? = null) : GoalEvent()
    data class ConfirmationRequired(val toolName: String, val argsJson: String) : GoalEvent()
    data class AgentEvent(val event: com.jarvis.core.agent.AgentEvent) : GoalEvent()
    data class Completed(
        val goalId: String,
        val summary: String,
        val executionResult: ExecutionResult? = null,
    ) : GoalEvent()
    data class Failed(
        val goalId: String,
        val code: String,
        val reason: String,
        val executionResult: ExecutionResult? = null,
    ) : GoalEvent()
    data class Cancelled(
        val goalId: String,
        val reason: String,
        val executionResult: ExecutionResult? = null,
    ) : GoalEvent()
}

/**
 * Assistant Goal Router coordinating LLM-centric reasoning, step dictation,
 * tool invocation, and verification.
 *
 * The LLM is reserved for complex reasoning and multi-step planning.
 * Simple, high-confidence native actions are handled by the local Needle layer and
 * always pass through ToolExecutor.
 */
@Singleton
class GoalEngine @Inject constructor(
    private val agentRunner: AgentRunner,
    private val taskRepository: TaskRepository,
    private val toolExecutor: ToolExecutor,
    private val needleRouter: NeedleRouter,
) {
    /**
     * Executes a user goal end-to-end with the LLM as the central cognitive engine.
     */
    fun executeGoal(goal: AssistantGoal): Flow<GoalEvent> = flow {
        emit(GoalEvent.StatusChanged("Processing goal..."))

        // Fast local action layer check via Needle
        val routingDecision = try {
            needleRouter.route(goal.goalDescription)
        } catch (e: Exception) {
            RoutingDecision.Escalate(EscalationReason.MALFORMED_RESULT, e.message)
        }

        if (routingDecision is RoutingDecision.Direct) {
            emit(GoalEvent.StatusChanged(routingDecision.userFacingAction))
            val outcome = toolExecutor.execute(
                toolName = routingDecision.toolName,
                argsJson = routingDecision.argsJson,
                agentRunId = goal.id,
                confirmationGate = goal.confirmationGate,
                forceConfirm = goal.forceConfirm,
                onConfirmationRequired = { name, args ->
                    emit(GoalEvent.ConfirmationRequired(name, args))
                },
            )

            if (outcome.cancelled) {
                updateTaskState(goal.id, TaskState.CANCELLED, "Action was cancelled by user")
                val execResult = ExecutionResult.failure(
                    code = ErrorCode.USER_CANCELLED,
                    message = "Action was cancelled by user",
                    userMessage = "Action was cancelled.",
                )
                emit(GoalEvent.Cancelled(goal.id, "Action was cancelled by user", execResult))
                return@flow
            }

            if (outcome.rejected || !outcome.success) {
                val errCode = outcome.errorCode ?: ErrorCode.TOOL_EXECUTION_FAILED
                updateTaskState(goal.id, TaskState.FAILED, outcome.observationText)
                val execResult = ExecutionResult.failure(
                    code = errCode,
                    message = outcome.observationText,
                    userMessage = outcome.userFacingState?.message ?: outcome.observationText,
                )
                emit(GoalEvent.Failed(goal.id, errCode.name, outcome.observationText, execResult))
                return@flow
            }

            updateTaskState(goal.id, TaskState.COMPLETED)
            emit(
                GoalEvent.AgentEvent(
                    com.jarvis.core.agent.AgentEvent.ToolExecuted(
                        name = outcome.toolName,
                        success = true,
                        observationText = outcome.observationText,
                    ),
                ),
            )
            val cleanSummary = com.jarvis.core.agent.execution.ToolResultResponseDeriver.deriveConciseResponse(
                toolName = outcome.toolName,
                observationText = outcome.observationText,
                success = outcome.success,
                errorCode = outcome.errorCode,
            )
            val execResult = ExecutionResult.success(
                message = cleanSummary,
                userMessage = cleanSummary,
                completedSteps = listOf(outcome.toolName),
            )
            emit(GoalEvent.Completed(goal.id, cleanSummary, execResult))
            return@flow
        }

        val effectiveMessages = goal.messages.ifEmpty {
            listOf(
                Message(
                    conversationId = goal.conversationId ?: "session_${System.currentTimeMillis()}",
                    role = MessageRole.USER,
                    content = goal.goalDescription,
                ),
            )
        }

        val request = AgentRunRequest(
            provider = goal.provider,
            modelId = goal.modelId,
            messages = effectiveMessages,
            agentRunId = goal.id,
            reasoningRequested = goal.reasoningRequested,
            memoryContext = goal.memoryContext,
            planFirst = goal.planFirst,
            isVoiceMode = goal.isVoiceMode,
            confirmationGate = goal.confirmationGate,
            forceConfirm = goal.forceConfirm,
        )

        var emittedTerminalEvent = false

        try {
            agentRunner.run(request).collect { agentEvent ->
                emit(GoalEvent.AgentEvent(agentEvent))
                when (agentEvent) {
                    is com.jarvis.core.agent.AgentEvent.ProgressMilestone -> {
                        emit(GoalEvent.MilestoneReached(agentEvent.message, agentEvent.step))
                    }
                    is com.jarvis.core.agent.AgentEvent.ConfirmationRequired -> {
                        emit(GoalEvent.ConfirmationRequired(agentEvent.name, agentEvent.argsJson))
                    }
                    is com.jarvis.core.agent.AgentEvent.FinalAnswer -> {
                        if (!emittedTerminalEvent) {
                            emittedTerminalEvent = true
                            val res = agentEvent.executionResult
                            when {
                                res != null && res.isCancelled -> {
                                    updateTaskState(goal.id, TaskState.CANCELLED, res.message)
                                    emit(GoalEvent.Cancelled(goal.id, res.message, res))
                                }
                                res != null && !res.isSuccess -> {
                                    val code = res.code ?: ErrorCode.UNKNOWN_ERROR
                                    updateTaskState(goal.id, TaskState.FAILED, res.message)
                                    emit(GoalEvent.Failed(goal.id, code.name, res.message, res))
                                }
                                else -> {
                                    updateTaskState(goal.id, TaskState.COMPLETED)
                                    emit(GoalEvent.Completed(goal.id, agentEvent.text, res))
                                }
                            }
                        }
                    }
                    is com.jarvis.core.agent.AgentEvent.Failed -> {
                        emittedTerminalEvent = true
                        updateTaskState(goal.id, TaskState.FAILED, agentEvent.message)
                        emit(GoalEvent.Failed(goal.id, agentEvent.code.name, agentEvent.message, null))
                    }
                    is com.jarvis.core.agent.AgentEvent.Cancelled -> {
                        emittedTerminalEvent = true
                        updateTaskState(goal.id, TaskState.CANCELLED, agentEvent.reason)
                        emit(GoalEvent.Cancelled(goal.id, agentEvent.reason, null))
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (!emittedTerminalEvent) {
                updateTaskState(goal.id, TaskState.FAILED, e.message ?: "Execution failed")
                emit(GoalEvent.Failed(goal.id, ErrorCode.UNKNOWN_ERROR.name, e.message ?: "Execution failed", null))
            }
        }
    }

    private suspend fun updateTaskState(taskId: String, state: TaskState, reason: String? = null) {
        try {
            taskRepository.updateState(taskId, state, reason)
        } catch (_: Exception) {
            // Task may not have been persisted upfront
        }
    }
}
