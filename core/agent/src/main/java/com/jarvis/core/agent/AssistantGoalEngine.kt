package com.jarvis.core.agent

import com.jarvis.core.agent.prompt.PromptBuilder
import com.jarvis.core.agent.prompt.PromptConfig
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.TaskRepository
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Execution mechanism chosen autonomously by the Assistant for a given goal.
 */
enum class ExecutionMechanism {
    /** Coordinated iterative multi-step tool execution via AgentRunner. */
    DYNAMIC_AGENT_LOOP,

    /** Direct deterministic idempotent task execution via TaskEngine. */
    DURABLE_TASK_PIPELINE,

    /** Immediate single-step fast conversational answer or synthesis. */
    DIRECT_ASSISTANT_REPLY,
}

/**
 * High-level goal submission to the Assistant Engine.
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
)

/**
 * Plan formulated for achieving the user's goal.
 */
data class AssistantPlan(
    val goalId: String,
    val mechanism: ExecutionMechanism,
    val explanation: String,
    val targetToolCategories: List<String> = emptyList(),
)

/**
 * Unified event stream emitted during goal execution.
 */
sealed class AssistantGoalEvent {
    data class Planned(val plan: AssistantPlan) : AssistantGoalEvent()
    data class StatusChanged(val status: String, val stepCount: Int = 0) : AssistantGoalEvent()
    data class ActionRequested(val toolName: String, val argsJson: String, val tier: PermissionTier) : AssistantGoalEvent()
    data class ActionApprovalRequired(val toolName: String, val argsJson: String) : AssistantGoalEvent()
    data class ActionExecuting(val toolName: String) : AssistantGoalEvent()
    data class ActionExecuted(val toolName: String, val success: Boolean, val observationText: String) : AssistantGoalEvent()
    data class ActionCancelled(val toolName: String) : AssistantGoalEvent()
    data class Completed(val summary: String) : AssistantGoalEvent()
    data class Failed(val code: String, val reason: String) : AssistantGoalEvent()
}

/**
 * Unified Assistant Goal Engine.
 *
 * Sits above raw chat loops and tool registries. The user expresses *goals*
 * through any interface (chat, voice, system intents, background routines),
 * and this engine determines the appropriate execution mechanism:
 * - Dynamic Agent Loop: when actions, tool calls, or real-time device interaction are needed.
 * - Direct Assistant: when direct knowledge synthesis is sufficient.
 *
 * Manages security, confirmation, cancellation, verification, and auditability.
 */
class AssistantGoalEngine(
    private val agentRunner: AgentRunner,
    private val taskEngine: TaskEngine,
    private val taskRepository: TaskRepository,
    private val notificationManager: AssistantNotificationManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Determines whether a user query represents an actionable device or external goal,
     * or a knowledge query.
     */
    fun evaluateExecutionMechanism(goalText: String, providerSupportsTools: Boolean): ExecutionMechanism {
        if (!providerSupportsTools) return ExecutionMechanism.DIRECT_ASSISTANT_REPLY
        return if (AgentTrigger.shouldUseAgent(goalText)) {
            ExecutionMechanism.DYNAMIC_AGENT_LOOP
        } else {
            ExecutionMechanism.DIRECT_ASSISTANT_REPLY
        }
    }

    /**
     * Executes a user goal end-to-end, returning a stream of AssistantGoalEvents.
     */
    fun executeGoal(
        goal: AssistantGoal,
    ): Flow<AssistantGoalEvent> = flow {
        val mechanism = evaluateExecutionMechanism(
            goal.goalDescription,
            goal.provider.capabilities.supportsTools,
        )

        val plan = AssistantPlan(
            goalId = goal.id,
            mechanism = mechanism,
            explanation = when (mechanism) {
                ExecutionMechanism.DYNAMIC_AGENT_LOOP -> "Executing multi-step assistant action plan"
                ExecutionMechanism.DURABLE_TASK_PIPELINE -> "Executing idempotent durable task pipeline"
                ExecutionMechanism.DIRECT_ASSISTANT_REPLY -> "Generating direct conversational response"
            },
        )
        emit(AssistantGoalEvent.Planned(plan))

        // Create or update durable Task record for auditability
        val durableTask = Task(
            id = goal.id,
            title = goal.goalDescription.take(60),
            goal = goal.goalDescription,
            triggerType = when (goal.source) {
                "routine" -> TaskTriggerType.ROUTINE
                "scheduled" -> TaskTriggerType.SCHEDULED
                else -> TaskTriggerType.MANUAL
            },
            state = TaskState.RUNNING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        withContext(ioDispatcher) {
            taskRepository.upsert(durableTask)
        }

        try {
            val agentRequest = AgentRunRequest(
                provider = goal.provider,
                modelId = goal.modelId,
                messages = goal.messages,
                agentRunId = goal.id,
                reasoningRequested = goal.reasoningRequested,
                memoryContext = goal.memoryContext,
                planFirst = goal.planFirst,
                isVoiceMode = goal.isVoiceMode,
            )

            var finalAnswer: String? = null
            var lastErrorCode: String? = null
            var lastErrorMessage: String? = null

            agentRunner.run(agentRequest).collect { event ->
                when (event) {
                    is AgentEvent.RunStarted -> {
                        emit(AssistantGoalEvent.StatusChanged("Starting goal execution"))
                    }
                    is AgentEvent.IterationStarted -> {
                        emit(AssistantGoalEvent.StatusChanged("Step ${event.step}", event.step))
                    }
                    is AgentEvent.ToolRequested -> {
                        emit(AssistantGoalEvent.ActionRequested(event.name, event.argsJson, event.tier))
                    }
                    is AgentEvent.ConfirmationRequired -> {
                        emit(AssistantGoalEvent.ActionApprovalRequired(event.name, event.argsJson))
                    }
                    is AgentEvent.ToolExecuting -> {
                        emit(AssistantGoalEvent.ActionExecuting(event.name))
                    }
                    is AgentEvent.ToolExecuted -> {
                        emit(AssistantGoalEvent.ActionExecuted(event.name, event.success, event.observationText))
                    }
                    is AgentEvent.ToolCancelled -> {
                        emit(AssistantGoalEvent.ActionCancelled(event.name))
                    }
                    is AgentEvent.ToolRejected -> {
                        emit(AssistantGoalEvent.ActionExecuted(event.name, false, event.reason))
                    }
                    is AgentEvent.FinalAnswer -> {
                        finalAnswer = event.text
                    }
                    is AgentEvent.Failed -> {
                        lastErrorCode = event.code
                        lastErrorMessage = event.message
                    }
                    is AgentEvent.StepCapReached -> {
                        emit(AssistantGoalEvent.StatusChanged("Step limit reached"))
                    }
                }
            }

            if (lastErrorMessage != null) {
                withContext(ioDispatcher) {
                    taskRepository.updateState(goal.id, TaskState.FAILED, lastErrorMessage)
                }
                emit(AssistantGoalEvent.Failed(lastErrorCode ?: "EXECUTION_ERROR", lastErrorMessage!!))
            } else {
                val completionSummary = finalAnswer ?: "Goal accomplished."
                withContext(ioDispatcher) {
                    taskRepository.updateState(goal.id, TaskState.COMPLETED)
                }
                emit(AssistantGoalEvent.Completed(completionSummary))
            }
        } catch (e: CancellationException) {
            withContext(ioDispatcher) {
                taskRepository.updateState(goal.id, TaskState.CANCELLED, "Cancelled by user or system")
            }
            throw e
        } catch (t: Throwable) {
            val errorMsg = t.message ?: "Unexpected error during goal execution"
            withContext(ioDispatcher) {
                taskRepository.updateState(goal.id, TaskState.FAILED, errorMsg)
            }
            emit(AssistantGoalEvent.Failed("INTERNAL_ERROR", errorMsg))
        }
    }
}
