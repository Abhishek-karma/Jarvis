package com.jarvis.core.agent

import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class TaskEngineEvent {
    data class StateChanged(val taskId: String, val newState: TaskState, val message: String? = null) : TaskEngineEvent()
    data class StepCompleted(val taskId: String, val stepIndex: Int, val description: String) : TaskEngineEvent()
    data class TaskProgress(val taskId: String, val progressRatio: Float) : TaskEngineEvent()
}

class TaskEngine(
    private val taskRepository: TaskRepository,
) {
    private val mutex = Mutex()
    private val _events = MutableSharedFlow<TaskEngineEvent>(extraBufferCapacity = 64)
    val events: Flow<TaskEngineEvent> = _events.asSharedFlow()

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
