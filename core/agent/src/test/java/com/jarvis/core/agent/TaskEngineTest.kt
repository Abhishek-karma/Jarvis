package com.jarvis.core.agent

import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.common.TaskTriggerType
import com.jarvis.core.database.repository.OperationRepository
import com.jarvis.core.database.repository.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TaskEngineTest {

    private class FakeTaskRepository : TaskRepository() {
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

    private class FakeOperationRepository : OperationRepository() {
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

    private fun createTestTool(
        name: String,
        tier: PermissionTier = PermissionTier.REVERSIBLE_WRITE,
        description: String = "Test tool",
        parametersSchemaJson: String = """{"type":"object"}""",
        action: suspend (String) -> ToolResult,
    ): Tool = object : Tool {
        override val name: String = name
        override val description: String = description
        override val parametersSchemaJson: String = parametersSchemaJson
        override val tier: PermissionTier = tier
        override suspend fun execute(argsJson: String): ToolResult = action(argsJson)
    }

    private fun newEngine(
        taskRepo: TaskRepository = FakeTaskRepository(),
        opRepo: OperationRepository = FakeOperationRepository(),
        registry: ToolRegistry = ToolRegistry(),
        auditLogger: AuditLogger = AuditLogger { },
    ): TaskEngine {
        val executor = ToolExecutor(
            registry = registry,
            audit = auditLogger,
            operationRepository = opRepo,
        )
        return TaskEngine(taskRepo, opRepo, executor)
    }

    @Test
    fun `idempotent tool execution runs once and returns recorded result on subsequent calls`() = runTest {
        val taskRepo = FakeTaskRepository()
        val opRepo = FakeOperationRepository()
        val registry = ToolRegistry()
        val executionCount = AtomicInteger(0)

        registry.register(
            createTestTool(name = "test_action") {
                executionCount.incrementAndGet()
                ToolResult(success = true, observationText = "action-result-123")
            },
        )

        val engine = newEngine(taskRepo, opRepo, registry)
        val task = Task(
            id = "task-1",
            title = "Test Task",
            goal = "Run test action",
            triggerType = TaskTriggerType.MANUAL,
            state = TaskState.RUNNING,
        )
        taskRepo.upsert(task)

        val result1 = engine.executeTool("task-1", "action_key_1", "test_action")
        val result2 = engine.executeTool("task-1", "action_key_1", "test_action")

        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)
        assertEquals("action-result-123", result1.getOrNull())
        assertEquals("action-result-123", result2.getOrNull())
        assertEquals(1, executionCount.get())
        assertTrue(engine.isOperationCompleted("action_key_1"))

        val persisted = opRepo.getByKey("action_key_1")!!
        assertEquals(OperationStatus.SUCCEEDED, persisted.status)
        assertEquals("action-result-123", persisted.resultJson)
        assertEquals("test_action", persisted.toolName)
    }

    @Test
    fun `idempotent tool execution is durable across fresh TaskEngine instances`() = runTest {
        val opRepo = FakeOperationRepository()
        val taskRepo = FakeTaskRepository()
        val registry = ToolRegistry()
        val invocations = AtomicInteger(0)

        registry.register(
            createTestTool(name = "test_worker") {
                invocations.incrementAndGet()
                ToolResult(success = true, observationText = "msg-1")
            },
        )

        val first = newEngine(taskRepo, opRepo, registry)
        first.executeTool("task-2", "worker_key_1", "test_worker")
        assertEquals(1, invocations.get())

        // Simulate process restart: a brand new TaskEngine sharing the same
        // ledger must not re-run the tool.
        val second = newEngine(taskRepo, opRepo, registry)
        val result = second.executeTool("task-2", "worker_key_1", "test_worker")

        assertEquals(1, invocations.get(), "second run must hit the ledger, not re-execute tool")
        assertEquals("msg-1", result.getOrNull())
    }

    @Test
    fun `a crashing prior run leaves the ledger in EXECUTING and is rejected`() = runTest {
        val opRepo = FakeOperationRepository()
        val registry = ToolRegistry()
        registry.register(
            createTestTool(name = "test_action") {
                ToolResult(success = true, observationText = "never")
            },
        )

        // Pre-populate as if a previous run had inserted but never finished.
        opRepo.insert(
            Operation(
                id = UUID.randomUUID().toString(),
                taskId = "task-3",
                toolName = "test_action",
                idempotencyKey = "action_key_3",
                status = OperationStatus.EXECUTING,
            ),
        )

        val engine = newEngine(FakeTaskRepository(), opRepo, registry)
        val result = engine.executeTool("task-3", "action_key_3", "test_action")

        assertTrue(result.isFailure, "in-flight legacy rows must be treated as failure, not retried silently")
    }

    @Test
    fun `a failing tool execution records FAILED and a retry returns the previous failure`() = runTest {
        val opRepo = FakeOperationRepository()
        val registry = ToolRegistry()
        val invocations = AtomicInteger(0)

        registry.register(
            createTestTool(name = "failing_action") {
                invocations.incrementAndGet()
                ToolResult(success = false, observationText = "network down")
            },
        )

        val engine = newEngine(FakeTaskRepository(), opRepo, registry)

        val first = engine.executeTool("task-4", "fail_key_4", "failing_action")
        assertTrue(first.isFailure)
        assertEquals(1, invocations.get())
        assertEquals(OperationStatus.FAILED, opRepo.getByKey("fail_key_4")?.status)

        val second = engine.executeTool("task-4", "fail_key_4", "failing_action")
        assertTrue(second.isFailure)
        assertEquals(1, invocations.get(), "retry must not re-execute the side effect")
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
    fun `recover orphaned tasks marks stale EXECUTING operation rows as UNKNOWN`() = runTest {
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
        assertEquals(OperationStatus.UNKNOWN, row?.status, "Interrupted operation must become UNKNOWN rather than FAILED")
        assertTrue(row?.errorMessage?.contains("uncertain") == true)
    }

    @Test
    fun `UNKNOWN operation is not silently re-executed`() = runTest {
        val opRepo = FakeOperationRepository()
        val registry = ToolRegistry()
        val invocations = AtomicInteger(0)

        registry.register(
            createTestTool(name = "send_email") {
                invocations.incrementAndGet()
                ToolResult(success = true, observationText = "email sent")
            },
        )

        // Pre-populate an UNKNOWN operation row (e.g. recovered from a crash)
        opRepo.insert(
            Operation(
                id = UUID.randomUUID().toString(),
                taskId = "task-unknown",
                toolName = "send_email",
                idempotencyKey = "email-key-1",
                status = OperationStatus.UNKNOWN,
                errorMessage = "Interrupted by app shutdown; outcome is uncertain.",
            ),
        )

        val engine = newEngine(FakeTaskRepository(), opRepo, registry)
        val result = engine.executeTool("task-unknown", "email-key-1", "send_email")

        assertTrue(result.isFailure, "UNKNOWN operations must not be silently re-executed")
        assertEquals(0, invocations.get(), "Side effect tool must NOT execute when operation status is UNKNOWN")
        assertTrue(result.exceptionOrNull()?.message?.contains("UNKNOWN") == true)
    }

    @Test
    fun `concurrent calls with same idempotency key execute tool only once`() = runTest {
        val taskRepo = FakeTaskRepository()
        val opRepo = FakeOperationRepository()
        val registry = ToolRegistry()
        val executionCount = AtomicInteger(0)

        registry.register(
            createTestTool(name = "payment_action") {
                executionCount.incrementAndGet()
                ToolResult(success = true, observationText = "payment-confirmed")
            },
        )

        val engine = newEngine(taskRepo, opRepo, registry)
        val task = Task(
            id = "task-concurrent",
            title = "Concurrent Task",
            goal = "Pay invoice",
            triggerType = TaskTriggerType.MANUAL,
            state = TaskState.RUNNING,
        )
        taskRepo.upsert(task)

        val res1 = engine.executeTool("task-concurrent", "payment-key-1", "payment_action")
        val res2 = engine.executeTool("task-concurrent", "payment-key-1", "payment_action")

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals(1, executionCount.get(), "Concurrent/sequential calls with identical key must deduplicate tool execution")
        assertEquals("payment-confirmed", res1.getOrNull())
        assertEquals("payment-confirmed", res2.getOrNull())
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
        val registry = ToolRegistry()
        registry.register(
            createTestTool(name = "dummy") {
                throw CancellationException("cancelled")
            },
        )
        val engine = newEngine(taskRepo, opRepo, registry)

        try {
            engine.executeTool("task-cancel", "cancel-key", "dummy")
        } catch (_: CancellationException) {
            // Expected propagation
        }

        assertEquals(
            OperationStatus.FAILED,
            opRepo.getByKey("cancel-key")?.status,
            "CancellationException must mark the ledger FAILED before rethrowing",
        )
    }

    @Test
    fun `recover orphaned tasks puts task with uncertain operation into WAITING_FOR_CONFIRMATION without re-queuing`() = runTest {
        val taskRepo = FakeTaskRepository()
        val opRepo = FakeOperationRepository()
        val engine = newEngine(taskRepo, opRepo)

        val staleTs = System.currentTimeMillis() - (TaskEngine.STALE_EXECUTING_THRESHOLD_MILLIS + 5_000)
        val task = Task(
            id = "task-with-uncertain-op",
            title = "Task with uncertain operation",
            goal = "Send critical payment",
            triggerType = TaskTriggerType.MANUAL,
            state = TaskState.RUNNING,
            retries = 0,
            maxRetries = 3,
        )
        taskRepo.upsert(task)

        opRepo.insert(
            Operation(
                id = UUID.randomUUID().toString(),
                taskId = "task-with-uncertain-op",
                toolName = "send_payment",
                idempotencyKey = "payment-key-uncertain",
                status = OperationStatus.EXECUTING,
                createdAt = staleTs,
                updatedAt = staleTs,
            ),
        )

        engine.recoverOrphanedTasks()

        // 1. Operation must be transitioned to UNKNOWN (NOT FAILED)
        val op = opRepo.getByKey("payment-key-uncertain")
        assertNotNull(op)
        assertEquals(OperationStatus.UNKNOWN, op?.status)

        // 2. Task must be in WAITING_FOR_CONFIRMATION (NOT QUEUED!)
        val recoveredTask = taskRepo.get("task-with-uncertain-op")
        assertNotNull(recoveredTask)
        assertEquals(TaskState.WAITING_FOR_CONFIRMATION, recoveredTask?.state)
        assertTrue(recoveredTask?.failureReason?.contains("uncertain") == true)
        // Retries must NOT have been incremented because it was not re-queued
        assertEquals(0, recoveredTask?.retries)
    }

    @Test
    fun `manual resolution of UNKNOWN operation transitions to SUCCEEDED or FAILED`() = runTest {
        val opRepo = FakeOperationRepository()
        val engine = newEngine(FakeTaskRepository(), opRepo)

        opRepo.insert(
            Operation(
                id = "op-unknown-1",
                taskId = "task-u",
                toolName = "transfer_funds",
                idempotencyKey = "transfer-1",
                status = OperationStatus.UNKNOWN,
            ),
        )

        // Resolve as SUCCEEDED
        val resolvedSuccess = engine.resolveUnknownOperation(
            idempotencyKey = "transfer-1",
            resolvedStatus = OperationStatus.SUCCEEDED,
            resultOrError = "Verified transferred externally",
        )
        assertTrue(resolvedSuccess)
        val opAfterSuccess = opRepo.getByKey("transfer-1")
        assertEquals(OperationStatus.SUCCEEDED, opAfterSuccess?.status)
        assertEquals("Verified transferred externally", opAfterSuccess?.resultJson)

        // Attempt resolving an operation that is no longer UNKNOWN fails
        val resolvedAgain = engine.resolveUnknownOperation(
            idempotencyKey = "transfer-1",
            resolvedStatus = OperationStatus.FAILED,
        )
        assertFalse(resolvedAgain)
    }

    @Test
    fun `state transition model enforces legal lifecycle transitions`() = runTest {
        // Legal direct transitions
        assertTrue(TaskEngine.isValidTransition(TaskState.SCHEDULED, TaskState.QUEUED))
        assertTrue(TaskEngine.isValidTransition(TaskState.SCHEDULED, TaskState.CANCELLED))
        assertTrue(TaskEngine.isValidTransition(TaskState.QUEUED, TaskState.RUNNING))
        assertTrue(TaskEngine.isValidTransition(TaskState.QUEUED, TaskState.CANCELLED))
        assertTrue(TaskEngine.isValidTransition(TaskState.RUNNING, TaskState.WAITING_FOR_CONFIRMATION))
        assertTrue(TaskEngine.isValidTransition(TaskState.RUNNING, TaskState.COMPLETED))
        assertTrue(TaskEngine.isValidTransition(TaskState.RUNNING, TaskState.FAILED))
        assertTrue(TaskEngine.isValidTransition(TaskState.RUNNING, TaskState.QUEUED))
        assertTrue(TaskEngine.isValidTransition(TaskState.RUNNING, TaskState.CANCELLED))
        assertTrue(TaskEngine.isValidTransition(TaskState.WAITING_FOR_CONFIRMATION, TaskState.RUNNING))
        assertTrue(TaskEngine.isValidTransition(TaskState.WAITING_FOR_CONFIRMATION, TaskState.QUEUED))
        assertTrue(TaskEngine.isValidTransition(TaskState.WAITING_FOR_CONFIRMATION, TaskState.FAILED))
        assertTrue(TaskEngine.isValidTransition(TaskState.WAITING_FOR_CONFIRMATION, TaskState.CANCELLED))

        // Illegal direct transitions
        assertFalse(TaskEngine.isValidTransition(TaskState.SCHEDULED, TaskState.RUNNING))
        assertFalse(TaskEngine.isValidTransition(TaskState.SCHEDULED, TaskState.COMPLETED))
        assertFalse(TaskEngine.isValidTransition(TaskState.COMPLETED, TaskState.RUNNING))
        assertFalse(TaskEngine.isValidTransition(TaskState.FAILED, TaskState.RUNNING))
        assertFalse(TaskEngine.isValidTransition(TaskState.CANCELLED, TaskState.RUNNING))

        // Explicit retry transitions
        assertFalse(TaskEngine.isValidTransition(TaskState.FAILED, TaskState.QUEUED, isExplicitRetry = false))
        assertTrue(TaskEngine.isValidTransition(TaskState.FAILED, TaskState.QUEUED, isExplicitRetry = true))
        assertTrue(TaskEngine.isValidTransition(TaskState.CANCELLED, TaskState.QUEUED, isExplicitRetry = true))
        assertTrue(TaskEngine.isValidTransition(TaskState.COMPLETED, TaskState.QUEUED, isExplicitRetry = true))
    }

    @Test
    fun `task lifecycle creation completion cancellation and retry`() = runTest {
        val taskRepo = FakeTaskRepository()
        val engine = newEngine(taskRepo)

        // 1. Create task
        val task = engine.createTask(
            title = "Test Lifecycle Task",
            goal = "Test full lifecycle",
        )
        assertEquals(TaskState.QUEUED, task.state)
        assertEquals("Test Lifecycle Task", task.title)

        // 2. Start task
        val started = engine.startTask(task.id)
        assertTrue(started)
        assertEquals(TaskState.RUNNING, taskRepo.get(task.id)?.state)

        // 3. Complete task
        val completed = engine.recordCompletion(task.id, """{"status":"ok"}""")
        assertTrue(completed)
        val completedTask = taskRepo.get(task.id)
        assertEquals(TaskState.COMPLETED, completedTask?.state)
        assertEquals("""{"status":"ok"}""", completedTask?.resultJson)

        // 4. Retry task explicitly
        val retried = engine.retryTask(task.id)
        assertTrue(retried)
        assertEquals(TaskState.QUEUED, taskRepo.get(task.id)?.state)

        // 5. Cancel task
        val cancelled = engine.cancelTask(task.id)
        assertTrue(cancelled)
        assertEquals(TaskState.CANCELLED, taskRepo.get(task.id)?.state)
    }
}
