package com.jarvis.feature.chat.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.core.agent.RoutineScheduler
import com.jarvis.core.database.repository.RoutineRepository
import com.jarvis.core.common.RoutineScheduleType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Executes one routine run when its WorkManager job fires.
 *
 * One-time routines are consumed (disabled) before execution so a WorkManager
 * retry after a crash cannot fire them twice. Recurring routines stay enabled:
 * [RoutineScheduler.runNow] re-enqueues the next run as part of this run.
 */
@HiltWorker
class RoutineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val routineScheduler: RoutineScheduler,
    private val routineRepository: RoutineRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val routineId = inputData.getString(KEY_ROUTINE_ID) ?: return Result.failure()
        val routine = routineRepository.get(routineId)
            ?: return Result.success() // deleted before the job fired
        if (!routine.enabled) return Result.success()

        if (routine.scheduleType == RoutineScheduleType.ONE_TIME) {
            routineRepository.upsert(
                routine.copy(enabled = false, updatedAt = System.currentTimeMillis()),
            )
        }

        return runCatching {
            routineScheduler.runNow(routineId)
        }.fold(
            onSuccess = { it.fold({ Result.success() }, { Result.failure() }) },
            onFailure = { Result.failure() },
        )
    }

    companion object {
        const val KEY_ROUTINE_ID = "routine_id"
    }
}
