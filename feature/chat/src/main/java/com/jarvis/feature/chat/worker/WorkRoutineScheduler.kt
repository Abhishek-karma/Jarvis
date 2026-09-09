package com.jarvis.feature.chat.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jarvis.core.agent.RoutineWorkScheduler
import com.jarvis.core.common.Routine
import java.util.concurrent.TimeUnit

/**
 * [RoutineWorkScheduler] backed by WorkManager: each routine owns one unique
 * one-time job at its [Routine.nextRunAt]; running the job reschedules the
 * next occurrence (chained one-time work gives us arbitrary intervals, which
 * PeriodicWorkRequest cannot express below 15 minutes).
 */
class WorkRoutineScheduler(
    private val workManager: WorkManager,
) : RoutineWorkScheduler {

    override suspend fun scheduleNext(routine: Routine) {
        val nextRunAt = routine.nextRunAt ?: return
        val delayMs = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInputData(workDataOf(RoutineWorker.KEY_ROUTINE_ID to routine.id))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(workName(routine.id), ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun cancel(routineId: String) {
        workManager.cancelUniqueWork(workName(routineId))
    }

    companion object {
        fun workName(routineId: String) = "routine-$routineId"
    }
}
