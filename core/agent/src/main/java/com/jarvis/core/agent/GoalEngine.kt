package com.jarvis.core.agent

import com.jarvis.core.common.Message
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.repository.TaskRepository
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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

/**
 * Unified Assistant Goal Engine.
 *
 * Routes goals directly through AgentRunner and tool loop without bloated regex strategies or false direct reply flags.
 */
@Singleton
class GoalEngine @Inject constructor(
    private val agentRunner: AgentRunner,
    private val taskRepository: TaskRepository,
    private val registry: ToolRegistry,
    private val audit: AuditLogger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Executes a user goal end-to-end via AgentRunner.
     */
    fun executeGoal(goal: AssistantGoal): Flow<GoalEvent> = flow {
        emit(GoalEvent.StatusChanged("Processing goal..."))

        // Never silently approve a sensitive action. Interactive callers must provide a
        // confirmation gate; non-interactive goals deny sensitive actions by default.
        val runner =
            if (goal.confirmationGate != null || goal.forceConfirm) {
                AgentRunner(
                    registry = registry,
                    audit = audit,
                    confirmationGate = goal.confirmationGate ?: ConfirmationGate { _, _ -> false },
                    forceConfirm = goal.forceConfirm,
                )
            } else {
                agentRunner
            }

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

        var finalAnswerText: String? = null
        var lastError: String? = null

        try {
            runner.run(agentRequest).collect { agentEvent ->
                when (agentEvent) {
                    is AgentEvent.ToolExecuting -> {
                        emit(GoalEvent.StatusChanged("Executing ${agentEvent.name}..."))
                    }
                    is AgentEvent.ConfirmationRequired -> {
                        emit(GoalEvent.ConfirmationRequired(
                            goalId = goal.id,
                            capabilityId = "tool",
                            toolName = agentEvent.name,
                            argsJson = agentEvent.argsJson,
                        ))
                    }
                    is AgentEvent.FinalAnswer -> {
                        finalAnswerText = agentEvent.text
                    }
                    is AgentEvent.Failed -> {
                        lastError = agentEvent.message
                    }
                    else -> Unit
                }
                emit(GoalEvent.AgentEvent(agentEvent))
            }

            if (lastError != null) {
                updateTaskState(goal.id, TaskState.FAILED, lastError)
                emit(GoalEvent.Failed(goal.id, "EXECUTION_ERROR", lastError!!))
            } else {
                val summary = finalAnswerText ?: "Goal completed."
                updateTaskState(goal.id, TaskState.COMPLETED)
                emit(GoalEvent.Completed(goal.id, summary))
            }
        } catch (e: CancellationException) {
            updateTaskState(goal.id, TaskState.CANCELLED, "Cancelled by user or system")
            emit(GoalEvent.Cancelled("Cancelled by user or system"))
            throw e
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unexpected error during goal execution"
            updateTaskState(goal.id, TaskState.FAILED, errorMsg)
            emit(GoalEvent.Failed(goal.id, "INTERNAL_ERROR", errorMsg))
        }
    }

    private suspend fun updateTaskState(taskId: String, state: TaskState, failureReason: String? = null) {
        withContext(ioDispatcher) {
            runCatching {
                taskRepository.updateState(taskId, state, failureReason)
            }
        }
    }
}

/**
 * Unified event stream emitted during goal execution.
 */
sealed class GoalEvent {
    data class StatusChanged(val message: String, val progress: Float = -1f) : GoalEvent()
    data class AgentEvent(val event: com.jarvis.core.agent.AgentEvent) : GoalEvent()
    data class ConfirmationRequired(
        val goalId: String,
        val capabilityId: String,
        val toolName: String,
        val argsJson: String,
    ) : GoalEvent()
    data class Completed(val goalId: String, val summary: String) : GoalEvent()
    data class Failed(val goalId: String, val code: String, val reason: String) : GoalEvent()
    data class Cancelled(val reason: String) : GoalEvent()
}
