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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

fun interface RoutineExecutionRunner {
    suspend fun executeRoutine(task: Task, routine: Routine): Result<String>
}

/**
 * Durable scheduling for routines. Backed by Android WorkManager in the app
 * layer so scheduled runs survive process death and device reboots —
 * previously nothing ever fired a routine whose time had come.
 */
interface RoutineWorkScheduler {
    /** Enqueue (or replace) a unique job that runs [routine] at [Routine.nextRunAt]. */
    suspend fun scheduleNext(routine: Routine)

    /** Remove any pending job for the routine. */
    suspend fun cancel(routineId: String)
}

class RoutineScheduler(
    private val routineRepository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val taskEngine: TaskEngine,
    private val runner: RoutineExecutionRunner? = null,
    private val notifications: AssistantNotificationManager? = null,
    private val workScheduler: RoutineWorkScheduler? = null,
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

            // Execute routine step-by-step through real runner
            val executionResult = if (runner != null) {
                runner.executeRoutine(task, routine)
            } else {
                Result.failure(IllegalStateException("No RoutineExecutionRunner configured to execute routine goal: ${routine.goal}"))
            }

            if (executionResult.isSuccess) {
                val summary = executionResult.getOrNull() ?: "Routine execution completed."
                taskEngine.recordCompletion(task.id, summary)
                notifications?.notifySucceeded(task.id, routine.name, summary)

                routineRepository.upsert(
                    routine.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastRunStatus = "success",
                        failureReason = null,
                        nextRunAt = nextRun,
                        updatedAt = System.currentTimeMillis(),
                    )
                )

                "Routine '${routine.name}' executed successfully: $summary"
            } else {
                val error = executionResult.exceptionOrNull() ?: Exception("Unknown routine error")
                taskEngine.recordFailure(task.id, error.message ?: "Execution failed")
                routineRepository.upsert(
                    routine.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastRunStatus = "failed",
                        failureReason = error.message,
                        nextRunAt = nextRun,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                notifications?.notifyFailed(routineId, routine.name, error.message ?: "Unknown execution error")
                throw error
            }
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
        }.also {
            // Keep the WorkManager chain alive: a recurring routine schedules its next run
            // even when this run failed, so the next interval still fires.
            scheduleNextWorkIfNeeded(routineId)
        }
    }

    /**
     * Re-enqueues WorkManager jobs for every enabled routine. Called on app start so
     * routines created (or left) before this run still fire on schedule. Idempotent:
     * unique work names + replace policy.
     */
    suspend fun syncAll() = withContext(ioDispatcher) {
        val scheduler = workScheduler ?: return@withContext
        for (routine in routineRepository.observeEnabled().first()) {
            if (routine.nextRunAt != null) scheduler.scheduleNext(routine) else scheduler.cancel(routine.id)
        }
    }

    private suspend fun scheduleNextWorkIfNeeded(routineId: String) {
        val scheduler = workScheduler ?: return
        val routine = routineRepository.get(routineId) ?: return
        if (!routine.enabled || routine.scheduleType != RoutineScheduleType.RECURRING) return
        if (routine.nextRunAt != null) scheduler.scheduleNext(routine)
    }

    suspend fun setEnabled(routineId: String, enabled: Boolean) = withContext(ioDispatcher) {
        val routine = routineRepository.get(routineId) ?: return@withContext
        val nextRun = if (enabled && routine.scheduleType == RoutineScheduleType.RECURRING) {
            calculateNextRunTime(routine.scheduleType, routine.cronOrInterval, System.currentTimeMillis())
        } else null

        val updated = routine.copy(
            enabled = enabled,
            nextRunAt = nextRun,
            updatedAt = System.currentTimeMillis(),
        )
        routineRepository.upsert(updated)
        if (enabled && nextRun != null) {
            workScheduler?.scheduleNext(updated)
        } else {
            workScheduler?.cancel(routineId)
        }
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
