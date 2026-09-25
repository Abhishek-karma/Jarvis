package com.jarvis.core.agent

import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.OperationRepository
import com.jarvis.core.database.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class TaskEngineEvent {
    data class StateChanged(val taskId: String, val newState: TaskState, val message: String? = null) : TaskEngineEvent()
    data class StepCompleted(val taskId: String, val stepIndex: Int, val description: String) : TaskEngineEvent()
    data class TaskProgress(val taskId: String, val progressRatio: Float) : TaskEngineEvent()
}

/**
 * Owns task lifecycle, durable task state, operation state tracking, and recovery.
 *
 * Explicit Boundaries:
 * - TaskEngine OWNS:
 *   - Task lifecycle (creation, state transitions, completion, failure, cancellation, retry)
 *   - Durable task state persistence via [TaskRepository]
 *   - Operation state inspection and resolution via [OperationRepository]
 *   - Orphan crash recovery (transitioning in-flight operations to UNKNOWN and halting uncertain tasks)
 *
 * - TaskEngine DOES NOT OWN:
 *   - Generic tool execution (owned by [ToolExecutor])
 *   - Agent loops (owned by AgentRunner)
 *   - Tool policy enforcement (owned by [ToolExecutor] & CommandPolicyEngine)
 *   - Tool confirmation dialogs / gating (owned by [ToolExecutor] & ConfirmationGate)
 *   - LLM reasoning (owned by Provider / AgentRunner)
 *
 * Task State Transition Model:
 * ```
 *   SCHEDULED
 *      │
 *      ▼
 *    QUEUED ──────────────────────────┐
 *      │                              │
 *      ▼                              ▼
 *   RUNNING ───────────────────────> CANCELLED (Terminal)
 *      │   │   │                      ▲
 *      │   │   └──────────────────────┤
 *      │   ▼                          │
 *      │  WAITING_FOR_CONFIRMATION ───┘
 *      │       │     │
 *      │       ▼     ▼
 *      │   RUNNING  FAILED (Terminal)
 *      │   (confirmed)
 *      ▼
 *   COMPLETED (Terminal)
 *      │
 *      ▼ (Explicit User Retry Only)
 *    QUEUED
 * ```
 *
 * Operation State Transition Model:
 * ```
 *   EXECUTING (in-flight)
 *      ├──> SUCCEEDED (completed normally)
 *      ├──> FAILED (known terminal failure during execution)
 *      └──> UNKNOWN (interrupted by crash/process death; outcome cannot be proven)
 *             │
 *             └──> Only explicit user confirmation / manual resolution may resolve UNKNOWN.
 *                  Automatic re-execution of UNKNOWN operations is strictly forbidden.
 * ```
 */
@Singleton
class TaskEngine @Inject constructor(
    private val taskRepository: TaskRepository,
    private val operationRepository: OperationRepository,
    private val toolExecutor: ToolExecutor,
) {
    private val mutex = Mutex()
    private val _events = MutableSharedFlow<TaskEngineEvent>(extraBufferCapacity = 64)
    val events: Flow<TaskEngineEvent> = _events.asSharedFlow()

    // =========================================================================
    // 1. Task Lifecycle & Durable Task State
    // =========================================================================

    /**
     * Creates and enqueues a new durable task.
     */
    suspend fun createTask(
        title: String,
        goal: String,
        triggerType: TaskTriggerType = TaskTriggerType.MANUAL,
        triggerConfigJson: String? = null,
        stepsJson: String = "[]",
        requiredPermissionsJson: String = "[]",
        maxRetries: Int = 3,
        initialState: TaskState = TaskState.QUEUED,
    ): Task = mutex.withLock {
        val now = System.currentTimeMillis()
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = title,
            goal = goal,
            triggerType = triggerType,
            triggerConfigJson = triggerConfigJson,
            state = initialState,
            stepsJson = stepsJson,
            requiredPermissionsJson = requiredPermissionsJson,
            maxRetries = maxRetries,
            retries = 0,
            createdAt = now,
            updatedAt = now,
        )
        taskRepository.upsert(task)
        _events.tryEmit(TaskEngineEvent.StateChanged(task.id, initialState, "Task created"))
        task
    }

    /**
     * Starts execution of a queued task, transitioning it to RUNNING.
     */
    suspend fun startTask(taskId: String): Boolean =
        transitionState(taskId, TaskState.RUNNING, "Starting task execution")

    /**
     * Transitions a task's state according to the formal state transition model.
     */
    suspend fun transitionState(
        taskId: String,
        targetState: TaskState,
        reason: String? = null,
        isExplicitRetry: Boolean = false,
    ): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false

        if (!isValidTransition(task.state, targetState, isExplicitRetry)) {
            return false
        }

        taskRepository.updateState(taskId, targetState, reason)
        _events.tryEmit(TaskEngineEvent.StateChanged(taskId, targetState, reason))
        true
    }

    /**
     * Records task completion and saves any result payload.
     */
    suspend fun recordCompletion(taskId: String, resultJson: String? = null): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false
        if (!isValidTransition(task.state, TaskState.COMPLETED)) {
            return false
        }
        val updated = task.copy(
            state = TaskState.COMPLETED,
            resultJson = resultJson,
            updatedAt = System.currentTimeMillis(),
        )
        taskRepository.upsert(updated)
        _events.tryEmit(TaskEngineEvent.StateChanged(taskId, TaskState.COMPLETED, "Completed successfully"))
        true
    }

    /**
     * Records task failure with an explanatory reason.
     */
    suspend fun recordFailure(taskId: String, failureReason: String): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false
        if (!isValidTransition(task.state, TaskState.FAILED)) {
            return false
        }
        val updated = task.copy(
            state = TaskState.FAILED,
            failureReason = failureReason,
            updatedAt = System.currentTimeMillis(),
        )
        taskRepository.upsert(updated)
        _events.tryEmit(TaskEngineEvent.StateChanged(taskId, TaskState.FAILED, failureReason))
        true
    }

    /**
     * Cancels an active or queued task.
     */
    suspend fun cancelTask(taskId: String): Boolean =
        transitionState(taskId, TaskState.CANCELLED, "User cancelled")

    /**
     * Explicitly retries a failed, cancelled, or completed task by re-queueing it.
     */
    suspend fun retryTask(taskId: String): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false
        if (!isValidTransition(task.state, TaskState.QUEUED, isExplicitRetry = true)) {
            return false
        }
        val updated = task.copy(
            state = TaskState.QUEUED,
            retries = 0,
            failureReason = null,
            updatedAt = System.currentTimeMillis(),
        )
        taskRepository.upsert(updated)
        _events.tryEmit(TaskEngineEvent.StateChanged(taskId, TaskState.QUEUED, "User retried task"))
        true
    }

    /**
     * Publishes progress for a running task.
     */
    fun updateProgress(taskId: String, progressRatio: Float) {
        _events.tryEmit(TaskEngineEvent.TaskProgress(taskId, progressRatio.coerceIn(0f, 1f)))
    }

    /**
     * Publishes a step completion event.
     */
    fun recordStepCompleted(taskId: String, stepIndex: Int, description: String) {
        _events.tryEmit(TaskEngineEvent.StepCompleted(taskId, stepIndex, description))
    }

    // =========================================================================
    // 2. Operation State Tracking & Tool Execution Delegation
    // =========================================================================

    /**
     * Executes a tool step idempotently for a task.
     *
     * Delegates entirely to [ToolExecutor] as the single canonical execution boundary.
     * TaskEngine only tracks step completion events and records task lifecycle updates.
     */
    suspend fun executeTool(
        taskId: String,
        idempotencyKey: String,
        toolName: String,
        argsJson: String = "{}",
        isExplicitRetry: Boolean = false,
    ): Result<String> {
        val outcome = toolExecutor.execute(
            toolName = toolName,
            argsJson = argsJson,
            agentRunId = taskId,
            idempotencyKey = idempotencyKey,
            isExplicitRetry = isExplicitRetry,
        )

        return if (outcome.success) {
            _events.tryEmit(TaskEngineEvent.StepCompleted(taskId, 0, "Executed $toolName"))
            Result.success(outcome.observationText)
        } else {
            val failureMsg = if (outcome.cancelled) {
                "Operation cancelled: ${outcome.observationText}"
            } else if (outcome.rejected) {
                outcome.rejectionReason ?: outcome.observationText
            } else {
                outcome.observationText
            }
            Result.failure(IllegalStateException(failureMsg))
        }
    }

    /** True iff the ledger has a SUCCEEDED row for this idempotency key. */
    suspend fun isOperationCompleted(idempotencyKey: String): Boolean =
        operationRepository.getByKey(idempotencyKey)?.status == OperationStatus.SUCCEEDED

    /** Retrieves an operation by its idempotency key. */
    suspend fun getOperation(idempotencyKey: String): Operation? =
        operationRepository.getByKey(idempotencyKey)

    /** Lists all recorded operations for a given task. */
    suspend fun listOperationsForTask(taskId: String): List<Operation> =
        operationRepository.listForTask(taskId)

    /**
     * Allows operator / user intervention to resolve an uncertain operation.
     */
    suspend fun resolveUnknownOperation(
        idempotencyKey: String,
        resolvedStatus: OperationStatus,
        resultOrError: String? = null,
    ): Boolean = mutex.withLock {
        val existing = operationRepository.getByKey(idempotencyKey) ?: return false
        if (existing.status != OperationStatus.UNKNOWN) return false

        val updated = when (resolvedStatus) {
            OperationStatus.SUCCEEDED -> existing.copy(
                status = OperationStatus.SUCCEEDED,
                resultJson = resultOrError,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
            OperationStatus.FAILED -> existing.copy(
                status = OperationStatus.FAILED,
                errorMessage = resultOrError ?: "Manually resolved as failed",
                updatedAt = System.currentTimeMillis(),
            )
            else -> return false
        }
        operationRepository.updateStatus(updated)
        true
    }

    // =========================================================================
    // 3. Orphan Recovery
    // =========================================================================

    /**
     * Recovers tasks and operations left in in-flight states after an unexpected process death or crash.
     *
     * Correct Recovery Model:
     * 1. If an operation was EXECUTING and process death makes its external result uncertain:
     *    - Mark it UNKNOWN (NOT FAILED).
     *    - Do NOT claim failure or success because external side effects may have already executed.
     * 2. Do NOT automatically re-execute UNKNOWN operations.
     * 3. Any task whose in-flight operation became UNKNOWN:
     *    - Must be placed into WAITING_FOR_CONFIRMATION (NOT QUEUED).
     *    - Must NOT be automatically re-executed to prevent duplicate external side effects.
     * 4. Running tasks with NO uncertain operations:
     *    - If retries remain (retries < maxRetries): re-queued (QUEUED) with retries incremented.
     *    - If max retries reached: marked FAILED.
     * 5. Tasks already WAITING_FOR_CONFIRMATION remain waiting for user review.
     */
    suspend fun recoverOrphanedTasks(
        staleThresholdMillis: Long = STALE_EXECUTING_THRESHOLD_MILLIS,
    ) = mutex.withLock {
        val now = System.currentTimeMillis()
        val staleTimeout = if (staleThresholdMillis > 0) now - staleThresholdMillis else Long.MAX_VALUE

        val executingOps = operationRepository.listExecuting()
        val uncertainTaskIds = mutableSetOf<String>()

        for (op in executingOps) {
            if (op.updatedAt <= staleTimeout) {
                operationRepository.updateStatus(
                    op.copy(
                        status = OperationStatus.UNKNOWN,
                        errorMessage = "Interrupted by app shutdown or process termination; external outcome is uncertain.",
                        updatedAt = now,
                    ),
                )
                uncertainTaskIds.add(op.taskId)
            }
        }

        val pending = taskRepository.getActiveOrPendingTasks()
        for (task in pending) {
            val taskOps = operationRepository.listForTask(task.id)
            val hasUncertainOps = task.id in uncertainTaskIds || taskOps.any { it.status == OperationStatus.UNKNOWN }

            if (hasUncertainOps) {
                // Uncertain operation detected: DO NOT re-queue or auto-execute.
                val updated = task.copy(
                    state = TaskState.WAITING_FOR_CONFIRMATION,
                    failureReason = "Interrupted operation with uncertain outcome requires review before proceeding.",
                    updatedAt = now,
                )
                taskRepository.upsert(updated)
                _events.tryEmit(
                    TaskEngineEvent.StateChanged(
                        task.id,
                        TaskState.WAITING_FOR_CONFIRMATION,
                        "Interrupted operation with uncertain outcome requires review",
                    ),
                )
            } else if (task.state == TaskState.RUNNING) {
                if (task.retries < task.maxRetries) {
                    val updated = task.copy(
                        state = TaskState.QUEUED,
                        retries = task.retries + 1,
                        failureReason = "Interrupted by app shutdown, re-queued (retry ${task.retries + 1}/${task.maxRetries})",
                        updatedAt = now,
                    )
                    taskRepository.upsert(updated)
                    _events.tryEmit(TaskEngineEvent.StateChanged(task.id, TaskState.QUEUED, "Re-queued after restart"))
                } else {
                    taskRepository.updateState(
                        id = task.id,
                        state = TaskState.FAILED,
                        failureReason = "Process interrupted and retry limit (${task.maxRetries}) reached.",
                    )
                    _events.tryEmit(TaskEngineEvent.StateChanged(task.id, TaskState.FAILED, "Max retries reached"))
                }
            } else if (task.state == TaskState.WAITING_FOR_CONFIRMATION) {
                _events.tryEmit(TaskEngineEvent.StateChanged(task.id, TaskState.WAITING_FOR_CONFIRMATION, "Awaiting user confirmation"))
            }
        }
    }

    companion object {
        /** Operations stuck in EXECUTING for longer than this are considered crashed and reclaimed on startup. */
        const val STALE_EXECUTING_THRESHOLD_MILLIS = 10 * 60 * 1000L

        /**
         * Validates legal direct state transitions in the Task lifecycle.
         */
        fun isValidTransition(from: TaskState, to: TaskState, isExplicitRetry: Boolean = false): Boolean {
            if (from == to) return true

            // Explicit retry allows transitioning terminal states back to QUEUED
            if (isExplicitRetry && to == TaskState.QUEUED) {
                return from in setOf(TaskState.FAILED, TaskState.CANCELLED, TaskState.COMPLETED)
            }

            return when (from) {
                TaskState.SCHEDULED -> to in listOf(TaskState.QUEUED, TaskState.CANCELLED)
                TaskState.QUEUED -> to in listOf(TaskState.RUNNING, TaskState.CANCELLED)
                TaskState.RUNNING -> to in listOf(
                    TaskState.WAITING_FOR_CONFIRMATION,
                    TaskState.COMPLETED,
                    TaskState.FAILED,
                    TaskState.QUEUED, // Transient failure re-queued
                    TaskState.CANCELLED,
                )
                TaskState.WAITING_FOR_CONFIRMATION -> to in listOf(
                    TaskState.RUNNING,
                    TaskState.QUEUED,
                    TaskState.FAILED,
                    TaskState.CANCELLED,
                )
                TaskState.COMPLETED,
                TaskState.FAILED,
                TaskState.CANCELLED -> false
            }
        }
    }
}

