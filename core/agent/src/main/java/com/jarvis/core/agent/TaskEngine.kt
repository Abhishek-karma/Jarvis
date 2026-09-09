package com.jarvis.core.agent

import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.repository.OperationRepository
import com.jarvis.core.database.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class TaskEngineEvent {
    data class StateChanged(val taskId: String, val newState: TaskState, val message: String? = null) : TaskEngineEvent()
    data class StepCompleted(val taskId: String, val stepIndex: Int, val description: String) : TaskEngineEvent()
    data class TaskProgress(val taskId: String, val progressRatio: Float) : TaskEngineEvent()
}

class TaskEngine(
    private val taskRepository: TaskRepository,
    private val operationRepository: OperationRepository,
) {
    private val mutex = Mutex()
    private val _events = MutableSharedFlow<TaskEngineEvent>(extraBufferCapacity = 64)
    val events: Flow<TaskEngineEvent> = _events.asSharedFlow()

    // In-process lock to serialize the "is this the first writer?" check across
    // threads. The durable UNIQUE constraint on the ledger's idempotencyKey
    // column is what actually guarantees exactly-once across processes and
    // across crashes; this just avoids racing threads both doing the work.
    private val operationLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Executes a side-effecting operation idempotently for a given task.
     *
     * Resolution rules:
     * 1. A persisted SUCCEEDED row with this key returns the recorded result.
     * 2. A persisted EXECUTING row from an in-flight prior run is treated as a
     *    crash: we return the existing row's result if present, otherwise a
     *    failure marker. The caller may retry by transitioning the row.
     * 3. Otherwise the block runs, the row is updated to SUCCEEDED with the
     *    serialized result, or FAILED with the error message.
     */
    suspend fun executeIdempotentOperation(
        taskId: String,
        idempotencyKey: String,
        toolName: String,
        action: suspend () -> String,
    ): Result<String> {
        val opLock = operationLocks.computeIfAbsent(idempotencyKey) { Mutex() }
        return opLock.withLock {
            operationRepository.getByKey(idempotencyKey)?.let { existing ->
                return when (existing.status) {
                    OperationStatus.SUCCEEDED -> Result.success(
                        existing.resultJson ?: "Operation $idempotencyKey was already completed in prior run.",
                    )
                    OperationStatus.FAILED -> Result.failure(
                        IllegalStateException(existing.errorMessage ?: "Operation previously failed"),
                    )
                    OperationStatus.EXECUTING -> Result.failure(
                        IllegalStateException("Operation $idempotencyKey was in-flight when the previous run crashed"),
                    )
                }
            }

            val now = System.currentTimeMillis()
            val operationId = UUID.randomUUID().toString()
            operationRepository.insert(
                Operation(
                    id = operationId,
                    taskId = taskId,
                    toolName = toolName,
                    idempotencyKey = idempotencyKey,
                    status = OperationStatus.EXECUTING,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            runCatching {
                val outcome = action()
                operationRepository.updateStatus(
                    Operation(
                        id = operationId,
                        taskId = taskId,
                        toolName = toolName,
                        idempotencyKey = idempotencyKey,
                        status = OperationStatus.SUCCEEDED,
                        resultJson = outcome,
                        createdAt = now,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                _events.tryEmit(TaskEngineEvent.StepCompleted(taskId, 0, "Executed operation: $idempotencyKey"))
                outcome
            }.onFailure { err ->
                operationRepository.updateStatus(
                    Operation(
                        id = operationId,
                        taskId = taskId,
                        toolName = toolName,
                        idempotencyKey = idempotencyKey,
                        status = OperationStatus.FAILED,
                        errorMessage = err.message,
                        createdAt = now,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** True iff the ledger has a SUCCEEDED row for this idempotency key. */
    suspend fun isOperationCompleted(idempotencyKey: String): Boolean =
        operationRepository.getByKey(idempotencyKey)?.status == OperationStatus.SUCCEEDED

    /**
     * Recovers any tasks left in RUNNING or WAITING state when the process restarts.
     * Prevents orphaned running tasks or duplicate side effects.
     */
    suspend fun recoverOrphanedTasks() = mutex.withLock {
        val pending = taskRepository.getActiveOrPendingTasks()
        for (task in pending) {
            if (task.state == TaskState.RUNNING) {
                if (task.retries < task.maxRetries) {
                    val updated = task.copy(
                        state = TaskState.QUEUED,
                        retries = task.retries + 1,
                        failureReason = "Interrupted by app shutdown, re-queued (retry ${task.retries + 1}/${task.maxRetries})",
                        updatedAt = System.currentTimeMillis(),
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
                // Keep it in waiting or mark for user re-confirmation
                _events.tryEmit(TaskEngineEvent.StateChanged(task.id, TaskState.WAITING_FOR_CONFIRMATION, "Awaiting user confirmation"))
            }
        }
    }

    suspend fun transitionState(
        taskId: String,
        targetState: TaskState,
        reason: String? = null,
    ): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false

        val valid = isValidTransition(task.state, targetState)
        if (!valid) return false

        taskRepository.updateState(taskId, targetState, reason)
        _events.tryEmit(TaskEngineEvent.StateChanged(taskId, targetState, reason))
        return true
    }

    suspend fun recordCompletion(taskId: String, resultJson: String?): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false
        val updated = task.copy(
            state = TaskState.COMPLETED,
            resultJson = resultJson,
            updatedAt = System.currentTimeMillis(),
        )
        taskRepository.upsert(updated)
        _events.tryEmit(TaskEngineEvent.StateChanged(taskId, TaskState.COMPLETED, "Completed successfully"))
        return true
    }

    suspend fun recordFailure(taskId: String, failureReason: String): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false
        val updated = task.copy(
            state = TaskState.FAILED,
            failureReason = failureReason,
            updatedAt = System.currentTimeMillis(),
        )
        taskRepository.upsert(updated)
        _events.tryEmit(TaskEngineEvent.StateChanged(taskId, TaskState.FAILED, failureReason))
        return true
    }

    suspend fun cancelTask(taskId: String): Boolean = transitionState(taskId, TaskState.CANCELLED, "User cancelled")

    suspend fun retryTask(taskId: String): Boolean = mutex.withLock {
        val task = taskRepository.get(taskId) ?: return false
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

    companion object {
        fun isValidTransition(from: TaskState, to: TaskState): Boolean {
            if (from == to) return true
            return when (from) {
                TaskState.SCHEDULED -> to in listOf(TaskState.QUEUED, TaskState.CANCELLED)
                TaskState.QUEUED -> to in listOf(TaskState.RUNNING, TaskState.CANCELLED)
                TaskState.RUNNING -> to in listOf(
                    TaskState.WAITING_FOR_CONFIRMATION,
                    TaskState.COMPLETED,
                    TaskState.FAILED,
                    TaskState.QUEUED, // on retry
                    TaskState.CANCELLED,
                )
                TaskState.WAITING_FOR_CONFIRMATION -> to in listOf(
                    TaskState.RUNNING,
                    TaskState.FAILED,
                    TaskState.CANCELLED,
                )
                TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED -> false
            }
        }
    }
}
