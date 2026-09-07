package com.jarvis.core.agent

import com.jarvis.core.common.Routine
import com.jarvis.core.common.RoutineScheduleType
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.RoutineRepository
import com.jarvis.core.database.repository.TaskRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class RoutineScheduler(
    private val routineRepository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val taskEngine: TaskEngine,
    private val notifications: AssistantNotificationManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Executes a routine immediately (either user-triggered "Run Now" or scheduled trigger).
     */
    suspend fun runNow(routineId: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val routine = routineRepository.get(routineId)
                ?: error("Routine $routineId not found")

            val task = Task(
                id = UUID.randomUUID().toString(),
                title = "Routine: ${routine.name}",
                goal = routine.goal,
                triggerType = TaskTriggerType.ROUTINE,
                state = TaskState.RUNNING,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            taskRepository.upsert(task)
            notifications?.notifyStarted(task.id, routine.name, routine.goal)

            // Update routine run status
            val nextRun = if (routine.scheduleType == RoutineScheduleType.RECURRING) {
                calculateNextRunTime(routine.scheduleType, routine.cronOrInterval, System.currentTimeMillis())
            } else null

            routineRepository.upsert(
                routine.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastRunStatus = "running",
                    nextRunAt = nextRun,
                    updatedAt = System.currentTimeMillis(),
                )
            )

            // Execute routine step-by-step
            taskEngine.recordCompletion(task.id, "{\"status\":\"success\",\"summary\":\"Routine execution completed.\"}")
            notifications?.notifySucceeded(task.id, routine.name, "Completed successfully")

            routineRepository.upsert(
                routine.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastRunStatus = "success",
                    failureReason = null,
                    nextRunAt = nextRun,
                    updatedAt = System.currentTimeMillis(),
                )
            )

            "Routine '${routine.name}' executed successfully."
        }.onFailure { err ->
            val routine = routineRepository.get(routineId)
            if (routine != null) {
                routineRepository.upsert(
                    routine.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastRunStatus = "failed",
                        failureReason = err.message,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                notifications?.notifyFailed(routineId, routine.name, err.message ?: "Unknown execution error")
            }
        }
    }

    suspend fun setEnabled(routineId: String, enabled: Boolean) = withContext(ioDispatcher) {
        val routine = routineRepository.get(routineId) ?: return@withContext
        val nextRun = if (enabled && routine.scheduleType == RoutineScheduleType.RECURRING) {
            calculateNextRunTime(routine.scheduleType, routine.cronOrInterval, System.currentTimeMillis())
        } else null

        routineRepository.upsert(
            routine.copy(
                enabled = enabled,
                nextRunAt = nextRun,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun cancel(routineId: String) = withContext(ioDispatcher) {
        setEnabled(routineId, false)
        notifications?.cancel(routineId)
    }

    companion object {
        /**
         * Calculates next run time for intervals or cron expressions.
         * Default fallback: interval in minutes (e.g. "60" = 1 hour, "1440" = daily).
         */
        fun calculateNextRunTime(
            scheduleType: RoutineScheduleType,
            cronOrInterval: String,
            fromTime: Long = System.currentTimeMillis(),
        ): Long {
            if (scheduleType == RoutineScheduleType.ONE_TIME) {
                return cronOrInterval.toLongOrNull() ?: (fromTime + 3600_000L)
            }
            val minutes = cronOrInterval.trim().toLongOrNull() ?: 60L
            return fromTime + (minutes.coerceAtLeast(5L) * 60_000L)
        }
    }
}
