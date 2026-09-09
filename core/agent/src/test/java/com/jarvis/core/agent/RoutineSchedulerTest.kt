package com.jarvis.core.agent

import com.jarvis.core.common.Operation
import com.jarvis.core.common.Routine
import com.jarvis.core.common.RoutineScheduleType
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.repository.OperationRepository
import com.jarvis.core.database.repository.RoutineRepository
import com.jarvis.core.database.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class RoutineSchedulerTest {

    private class NoopOperationRepository : OperationRepository {
        override suspend fun getByKey(key: String): Operation? = null
        override suspend fun insert(operation: Operation) {}
        override suspend fun updateStatus(operation: Operation) {}
        override suspend fun listForTask(taskId: String): List<Operation> = emptyList()
    }

    private class FakeRoutineRepository : RoutineRepository {
        val routines = mutableMapOf<String, Routine>()

        override suspend fun upsert(routine: Routine) {
            routines[routine.id] = routine
        }

        override suspend fun get(id: String): Routine? = routines[id]

        override fun observeAll(): Flow<List<Routine>> = flowOf(routines.values.toList())

        override fun observeEnabled(): Flow<List<Routine>> = flowOf(routines.values.filter { it.enabled })

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            val r = routines[id] ?: return
            routines[id] = r.copy(enabled = enabled)
        }

        override suspend fun delete(id: String) {
            routines.remove(id)
        }
    }

    private class FakeTaskRepository : TaskRepository {
        val tasks = mutableMapOf<String, Task>()
        override suspend fun upsert(task: Task) { tasks[task.id] = task }
        override suspend fun get(id: String): Task? = tasks[id]
        override suspend fun getActiveOrPendingTasks(): List<Task> = tasks.values.toList()
        override suspend fun updateState(id: String, state: TaskState, failureReason: String?) {
            val t = tasks[id] ?: return
            tasks[id] = t.copy(state = state, failureReason = failureReason)
        }
        override fun observeAll(): Flow<List<Task>> = flowOf(tasks.values.toList())
        override fun observeByState(state: TaskState): Flow<List<Task>> = flowOf(tasks.values.filter { it.state == state })
        override suspend fun delete(id: String) { tasks.remove(id) }
    }

    @Test
    fun `runNow executes via runner and updates routine status to success`() = runTest {
        val routineRepo = FakeRoutineRepository()
        val taskRepo = FakeTaskRepository()
        val taskEngine = TaskEngine(taskRepo, NoopOperationRepository())

        val routine = Routine(
            id = "routine-morning",
            name = "Morning Briefing",
            goal = "Fetch weather and calendar",
            scheduleType = RoutineScheduleType.ONE_TIME,
            cronOrInterval = "daily",
            enabled = true,
        )
        routineRepo.upsert(routine)

        val runnerExecuted = AtomicBoolean(false)
        val runner = RoutineExecutionRunner { task, r ->
            runnerExecuted.set(true)
            Result.success("Weather is sunny, 2 calendar events today.")
        }

        val scheduler = RoutineScheduler(
            routineRepository = routineRepo,
            taskRepository = taskRepo,
            taskEngine = taskEngine,
            runner = runner,
        )

        val result = scheduler.runNow("routine-morning")

        assertTrue(result.isSuccess)
        assertTrue(runnerExecuted.get())
        val updatedRoutine = routineRepo.get("routine-morning")
        assertEquals("success", updatedRoutine?.lastRunStatus)
        assertEquals(null, updatedRoutine?.failureReason)
    }

    @Test
    fun `runNow records failure when runner fails`() = runTest {
        val routineRepo = FakeRoutineRepository()
        val taskRepo = FakeTaskRepository()
        val taskEngine = TaskEngine(taskRepo, NoopOperationRepository())

        val routine = Routine(
            id = "routine-fail",
            name = "Failing Routine",
            goal = "Do impossible task",
            scheduleType = RoutineScheduleType.ONE_TIME,
            cronOrInterval = "daily",
            enabled = true,
        )
        routineRepo.upsert(routine)

        val runner = RoutineExecutionRunner { _, _ ->
            Result.failure(RuntimeException("Network timeout"))
        }

        val scheduler = RoutineScheduler(
            routineRepository = routineRepo,
            taskRepository = taskRepo,
            taskEngine = taskEngine,
            runner = runner,
        )

        val result = scheduler.runNow("routine-fail")

        assertTrue(result.isFailure)
        val updatedRoutine = routineRepo.get("routine-fail")
        assertEquals("failed", updatedRoutine?.lastRunStatus)
        assertEquals("Network timeout", updatedRoutine?.failureReason)
    }

    private class RecordingWorkScheduler : RoutineWorkScheduler {
        val scheduled = mutableListOf<Routine>()
        val cancelled = mutableListOf<String>()

        override suspend fun scheduleNext(routine: Routine) {
            scheduled += routine
        }

        override suspend fun cancel(routineId: String) {
            cancelled += routineId
        }
    }

    @Test
    fun `setEnabled(true) schedules a recurring routine in WorkManager`() = runTest {
        val routineRepo = FakeRoutineRepository()
        val taskRepo = FakeTaskRepository()
        val workScheduler = RecordingWorkScheduler()
        val scheduler = RoutineScheduler(
            routineRepository = routineRepo,
            taskRepository = taskRepo,
            taskEngine = TaskEngine(taskRepo, NoopOperationRepository()),
            workScheduler = workScheduler,
        )

        val routine = Routine(
            id = "routine-recur",
            name = "Hourly",
            goal = "Ping",
            scheduleType = RoutineScheduleType.RECURRING,
            cronOrInterval = "60",
            enabled = true,
        )
        routineRepo.upsert(routine)

        scheduler.setEnabled("routine-recur", true)

        assertEquals(1, workScheduler.scheduled.size)
        assertEquals("routine-recur", workScheduler.scheduled.single().id)
        assertTrue(workScheduler.scheduled.single().nextRunAt != null)
    }

    @Test
    fun `setEnabled(false) cancels the pending WorkManager job`() = runTest {
        val routineRepo = FakeRoutineRepository()
        val taskRepo = FakeTaskRepository()
        val workScheduler = RecordingWorkScheduler()
        val scheduler = RoutineScheduler(
            routineRepository = routineRepo,
            taskRepository = taskRepo,
            taskEngine = TaskEngine(taskRepo, NoopOperationRepository()),
            workScheduler = workScheduler,
        )

        routineRepo.upsert(
            Routine(
                id = "routine-off",
                name = "Off",
                goal = "Nothing",
                scheduleType = RoutineScheduleType.RECURRING,
                cronOrInterval = "60",
                enabled = false,
            ),
        )

        scheduler.setEnabled("routine-off", false)

        assertTrue(workScheduler.cancelled.contains("routine-off"))
        assertEquals(0, workScheduler.scheduled.size)
    }

    @Test
    fun `a successful recurring runNow re-arms the next WorkManager job`() = runTest {
        val routineRepo = FakeRoutineRepository()
        val taskRepo = FakeTaskRepository()
        val workScheduler = RecordingWorkScheduler()
        val scheduler = RoutineScheduler(
            routineRepository = routineRepo,
            taskRepository = taskRepo,
            taskEngine = TaskEngine(taskRepo, NoopOperationRepository()),
            runner = RoutineExecutionRunner { _, _ -> Result.success("ok") },
            workScheduler = workScheduler,
        )

        routineRepo.upsert(
            Routine(
                id = "routine-next",
                name = "Next",
                goal = "Tick",
                scheduleType = RoutineScheduleType.RECURRING,
                cronOrInterval = "60",
                enabled = true,
            ),
        )

        val result = scheduler.runNow("routine-next")

        assertTrue(result.isSuccess)
        assertEquals(1, workScheduler.scheduled.size, "next run must be re-scheduled")
        val next = workScheduler.scheduled.single().nextRunAt
        assertTrue(next != null && next > System.currentTimeMillis())
    }

    @Test
    fun `syncAll re-arms jobs for enabled routines and drops dangling ones`() = runTest {
        val routineRepo = FakeRoutineRepository()
        val taskRepo = FakeTaskRepository()
        val workScheduler = RecordingWorkScheduler()
        val scheduler = RoutineScheduler(
            routineRepository = routineRepo,
            taskRepository = taskRepo,
            taskEngine = TaskEngine(taskRepo, NoopOperationRepository()),
            workScheduler = workScheduler,
        )

        val now = System.currentTimeMillis()
        routineRepo.upsert(
            Routine(
                id = "r-scheduled",
                name = "S",
                goal = "g",
                scheduleType = RoutineScheduleType.RECURRING,
                cronOrInterval = "60",
                enabled = true,
                nextRunAt = now + 60_000,
            ),
        )
        routineRepo.upsert(
            Routine(
                id = "r-dangling",
                name = "D",
                goal = "g",
                scheduleType = RoutineScheduleType.ONE_TIME,
                cronOrInterval = "60",
                enabled = true,
                nextRunAt = null,
            ),
        )

        scheduler.syncAll()

        assertEquals(listOf("r-scheduled"), workScheduler.scheduled.map { it.id })
        assertEquals(listOf("r-dangling"), workScheduler.cancelled)
    }
}
