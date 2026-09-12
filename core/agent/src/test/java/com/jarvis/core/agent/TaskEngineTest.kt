package com.jarvis.core.agent

import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.OperationRepository
import com.jarvis.core.database.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TaskEngineTest {

    private class FakeTaskRepository : TaskRepository {
        private val tasks = mutableMapOf<String, Task>()

        override suspend fun upsert(task: Task) {
            tasks[task.id] = task
        }

        override suspend fun get(id: String): Task? = tasks[id]

        override suspend fun getActiveOrPendingTasks(): List<Task> {
            return tasks.values.filter {
                it.state == TaskState.RUNNING || it.state == TaskState.WAITING_FOR_CONFIRMATION || it.state == TaskState.QUEUED
            }
        }

        override suspend fun updateState(id: String, state: TaskState, failureReason: String?) {
            val existing = tasks[id] ?: return
            tasks[id] = existing.copy(
                state = state,
                failureReason = failureReason,
                updatedAt = System.currentTimeMillis(),
            )
        }

        override fun observeAll(): Flow<List<Task>> = flowOf(tasks.values.toList())

        override fun observeByState(state: TaskState): Flow<List<Task>> =
            flowOf(tasks.values.filter { it.state == state })

        override suspend fun delete(id: String) {
            tasks.remove(id)
        }
    }

    private class FakeOperationRepository : OperationRepository {
        private val byKey = mutableMapOf<String, Operation>()

        override suspend fun getByKey(key: String): Operation? = byKey[key]

        override suspend fun insert(operation: Operation) {
            // Honoring the real UNIQUE constraint: a duplicate insert is a hard
            // error, the same way Room's @Insert(ABORT) would behave. Tests
            // wanting to simulate a crash can pre-populate the map instead.
            check(operation.idempotencyKey !in byKey) {
                "UNIQUE constraint violated: ${operation.idempotencyKey}"
            }
            byKey[operation.idempotencyKey] = operation
        }

        override suspend fun updateStatus(operation: Operation) {
            byKey[operation.idempotencyKey] = operation
        }

        override suspend fun listForTask(taskId: String): List<Operation> =
            byKey.values.filter { it.taskId == taskId }.sortedBy { it.createdAt }

        override suspend fun listExecuting(): List<Operation> =
            byKey.values.filter { it.status == OperationStatus.EXECUTING }
    }

    private fun newEngine(
        taskRepo: TaskRepository = FakeTaskRepository(),
        opRepo: OperationRepository = FakeOperationRepository(),
    ): TaskEngine = TaskEngine(taskRepo, opRepo)

    @Test
    fun `idempotent operation executes once and returns recorded result on subsequent calls`() = runTest {
        val taskRepo = FakeTaskRepository()
        val opRepo = FakeOperationRepository()
        val engine = newEngine(taskRepo, opRepo)
        val task = Task(
            id = "task-1",
            title = "Test Task",
            goal = "Send message",
            triggerType = TaskTriggerType.MANUAL,
            state = TaskState.RUNNING,
        )
        taskRepo.upsert(task)

        val executionCount = AtomicInteger(0)
        val action: suspend () -> String = {
            executionCount.incrementAndGet()
            "message-sent-id-123"
        }

        val result1 = engine.executeIdempotentOperation("task-1", "send_msg_1", "send_sms", action)
        val result2 = engine.executeIdempotentOperation("task-1", "send_msg_1", "send_sms", action)

        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)
        assertEquals("message-sent-id-123", result1.getOrNull())
        assertEquals("message-sent-id-123", result2.getOrNull())
        assertEquals(1, executionCount.get())
        assertTrue(engine.isOperationCompleted("send_msg_1"))

        val persisted = opRepo.getByKey("send_msg_1")!!
        assertEquals(OperationStatus.SUCCEEDED, persisted.status)
        assertEquals("message-sent-id-123", persisted.resultJson)
        assertEquals("send_sms", persisted.toolName)
    }

    @Test
    fun `idempotent operation is durable across fresh TaskEngine instances`() = runTest {
        val opRepo = FakeOperationRepository()
        val taskRepo = FakeTaskRepository()

        val first = newEngine(taskRepo, opRepo)
        first.executeIdempotentOperation("task-2", "send_email_1", "send_email") {
            "msg-1"
        }

        // Simulate process restart: a brand new TaskEngine sharing the same
        // ledger must not re-run the side effect.
        val second = newEngine(taskRepo, opRepo)
        val invocations = AtomicInteger(0)
        val result = second.executeIdempotentOperation("task-2", "send_email_1", "send_email") {
            invocations.incrementAndGet()
            "msg-2"
        }

        assertEquals(0, invocations.get(), "second run must hit the ledger, not the action")
        assertEquals("msg-1", result.getOrNull())
    }

    @Test
    fun `a crashing prior run leaves the ledger in EXECUTING and is rejected`() = runTest {
        val opRepo = FakeOperationRepository()
        // Pre-populate as if a previous run had inserted but never finished.
        opRepo.insert(
            Operation(
                id = UUID.randomUUID().toString(),
                taskId = "task-3",
                toolName = "send_sms",
                idempotencyKey = "send_msg_3",
                status = OperationStatus.EXECUTING,
            ),
        )

        val engine = newEngine(FakeTaskRepository(), opRepo)
        val result = engine.executeIdempotentOperation("task-3", "send_msg_3", "send_sms") { "never" }

        assertTrue(result.isFailure, "in-flight legacy rows must be treated as failure, not retried silently")
    }

    @Test
    fun `a failing action records FAILED and a retry returns the previous failure`() = runTest {
        val opRepo = FakeOperationRepository()
        val engine = newEngine(FakeTaskRepository(), opRepo)

        val first = engine.executeIdempotentOperation("task-4", "send_msg_4", "send_sms") {
            error("network down")
        }
        assertTrue(first.isFailure)
        assertEquals(OperationStatus.FAILED, opRepo.getByKey("send_msg_4")?.status)

        val invocations = AtomicInteger(0)
        val second = engine.executeIdempotentOperation("task-4", "send_msg_4", "send_sms") {
            invocations.incrementAndGet()
            "should not run"
        }
        assertTrue(second.isFailure)
        assertEquals(0, invocations.get(), "retry must not re-execute the side effect")
        // The error message from the prior attempt is preserved on the row.
        assertNotEquals("should not run", second.exceptionOrNull()?.message)
    }

    @Test
    fun `recover orphaned tasks re-queues running tasks when under retry cap`() = runTest {
        val taskRepo = FakeTaskRepository()
        val engine = newEngine(taskRepo)
        val task = Task(
            id = "task-orphaned",
            title = "Orphaned Task",
            goal = "Compute stats",
            triggerType = TaskTriggerType.ROUTINE,
            state = TaskState.RUNNING,
            retries = 0,
            maxRetries = 3,
        )
        taskRepo.upsert(task)

        engine.recoverOrphanedTasks()

        val recovered = taskRepo.get("task-orphaned")
        assertEquals(TaskState.QUEUED, recovered?.state)
        assertEquals(1, recovered?.retries)
        assertTrue(recovered?.failureReason?.contains("Interrupted") == true)
    }

    @Test
    fun `recover orphaned tasks fails stale EXECUTING operation rows`() = runTest {
        val taskRepo = FakeTaskRepository()
        val opRepo = FakeOperationRepository()
        val engine = newEngine(taskRepo, opRepo)

        // Simulate a crash mid-operation by inserting an old EXECUTING row directly.
        // The row's updatedAt is set to well before the stale threshold.
        val staleTs = System.currentTimeMillis() - (TaskEngine.STALE_EXECUTING_THRESHOLD_MILLIS + 10_000)
        opRepo.insert(
            Operation(
                id = UUID.randomUUID().toString(),
                taskId = "task-stale",
                toolName = "create_file",
                idempotencyKey = "file-create-stale",
                status = OperationStatus.EXECUTING,
                createdAt = staleTs,
                updatedAt = staleTs,
            ),
        )

        engine.recoverOrphanedTasks()

        val row = opRepo.getByKey("file-create-stale")
        assertNotNull(row)
        assertEquals(OperationStatus.FAILED, row?.status)
        assertTrue(row?.errorMessage?.contains("app shutdown") == true)
    }

    @Test
    fun `recent EXECUTING rows are not reclaimed during recovery`() = runTest {
        val taskRepo = FakeTaskRepository()
        val opRepo = FakeOperationRepository()
        val engine = newEngine(taskRepo, opRepo)

        val freshTs = System.currentTimeMillis() - 1_000 // 1 second ago — within threshold
        opRepo.insert(
            Operation(
                id = UUID.randomUUID().toString(),
                taskId = "task-fresh",
                toolName = "create_file",
                idempotencyKey = "file-create-fresh",
                status = OperationStatus.EXECUTING,
                createdAt = freshTs,
                updatedAt = freshTs,
            ),
        )

        engine.recoverOrphanedTasks()

        val row = opRepo.getByKey("file-create-fresh")
        assertNotNull(row)
        assertEquals(OperationStatus.EXECUTING, row?.status)
    }

    @Test
    fun `cancellation exception rethrow leaves operation FAILED and propagates`() = runTest {
        val taskRepo = FakeTaskRepository()
        val opRepo = FakeOperationRepository()
        val engine = newEngine(taskRepo, opRepo)

        try {
            engine.executeIdempotentOperation("task-cancel", "cancel-key", "dummy") {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected propagation
        }

        assertEquals(
            OperationStatus.FAILED,
            opRepo.getByKey("cancel-key")?.status,
            "CancellationException must mark the ledger FAILED before rethrowing",
        )
    }
}
