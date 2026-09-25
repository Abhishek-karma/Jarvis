package com.jarvis.core.agent

import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.database.repository.OperationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ToolExecutorTest {

    private class FakeOperationRepository : OperationRepository() {
        private val byKey = mutableMapOf<String, Operation>()

        override suspend fun getByKey(key: String): Operation? = byKey[key]

        override suspend fun insert(operation: Operation) {
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
        parametersSchemaJson: String = """{"type":"object"}""",
        action: suspend (String) -> ToolResult,
    ): Tool = object : Tool {
        override val name: String = name
        override val description: String = "Test tool: $name"
        override val parametersSchemaJson: String = parametersSchemaJson
        override val tier: PermissionTier = tier
        override suspend fun execute(argsJson: String): ToolResult = action(argsJson)
    }

    @Test
    fun `side-effecting tool creates operation and deduplicates on second call`() = runTest {
        val registry = ToolRegistry()
        val opRepo = FakeOperationRepository()
        val execCount = AtomicInteger(0)

        registry.register(
            createTestTool(name = "send_message", tier = PermissionTier.REVERSIBLE_WRITE) {
                execCount.incrementAndGet()
                ToolResult(success = true, observationText = "Message sent successfully")
            },
        )

        val executor = ToolExecutor(
            registry = registry,
            audit = AuditLogger { },
            operationRepository = opRepo,
        )

        val out1 = executor.execute(
            toolName = "send_message",
            argsJson = "{}",
            agentRunId = "run-100",
            idempotencyKey = "msg-key-100",
        )
        val out2 = executor.execute(
            toolName = "send_message",
            argsJson = "{}",
            agentRunId = "run-100",
            idempotencyKey = "msg-key-100",
        )

        assertTrue(out1.success)
        assertTrue(out2.success)
        assertEquals(1, execCount.get(), "Side effect must only execute once")
        assertEquals("Message sent successfully", out1.observationText)
        assertEquals("Message sent successfully", out2.observationText)

        val recorded = opRepo.getByKey("msg-key-100")
        assertNotNull(recorded)
        assertEquals(OperationStatus.SUCCEEDED, recorded?.status)
    }

    @Test
    fun `uncertain operation status blocks automatic re-execution`() = runTest {
        val registry = ToolRegistry()
        val opRepo = FakeOperationRepository()
        val execCount = AtomicInteger(0)

        registry.register(
            createTestTool(name = "transfer_funds", tier = PermissionTier.SENSITIVE) {
                execCount.incrementAndGet()
                ToolResult(success = true, observationText = "Funds transferred")
            },
        )

        opRepo.insert(
            Operation(
                id = UUID.randomUUID().toString(),
                taskId = "run-200",
                toolName = "transfer_funds",
                idempotencyKey = "transfer-key-200",
                status = OperationStatus.UNKNOWN,
                errorMessage = "Interrupted by shutdown",
            ),
        )

        val executor = ToolExecutor(
            registry = registry,
            audit = AuditLogger { },
            operationRepository = opRepo,
        )

        val outcome = executor.execute(
            toolName = "transfer_funds",
            argsJson = "{}",
            agentRunId = "run-200",
            idempotencyKey = "transfer-key-200",
        )

        assertFalse(outcome.success)
        assertTrue(outcome.rejected)
        assertEquals(ErrorCode.OPERATION_UNCERTAIN, outcome.errorCode)
        assertEquals(0, execCount.get(), "Must not execute tool when status is UNKNOWN")
    }

    @Test
    fun `failed operation requires explicit retry to re-execute`() = runTest {
        val registry = ToolRegistry()
        val opRepo = FakeOperationRepository()
        val execCount = AtomicInteger(0)

        registry.register(
            createTestTool(name = "toggle_switch", tier = PermissionTier.REVERSIBLE_WRITE) {
                val c = execCount.incrementAndGet()
                if (c == 1) {
                    ToolResult(success = false, observationText = "Device busy")
                } else {
                    ToolResult(success = true, observationText = "Switch toggled")
                }
            },
        )

        val executor = ToolExecutor(
            registry = registry,
            audit = AuditLogger { },
            operationRepository = opRepo,
        )

        val out1 = executor.execute(
            toolName = "toggle_switch",
            argsJson = "{}",
            idempotencyKey = "toggle-key-1",
        )
        assertFalse(out1.success)
        assertEquals(1, execCount.get())
        assertEquals(OperationStatus.FAILED, opRepo.getByKey("toggle-key-1")?.status)

        // Non-explicit retry is rejected
        val out2 = executor.execute(
            toolName = "toggle_switch",
            argsJson = "{}",
            idempotencyKey = "toggle-key-1",
            isExplicitRetry = false,
        )
        assertFalse(out2.success)
        assertEquals(1, execCount.get(), "Should not re-execute without explicit retry")

        // Explicit retry proceeds
        val out3 = executor.execute(
            toolName = "toggle_switch",
            argsJson = "{}",
            idempotencyKey = "toggle-key-1",
            isExplicitRetry = true,
        )
        assertTrue(out3.success)
        assertEquals(2, execCount.get(), "Explicit retry must execute tool")
        assertEquals(OperationStatus.SUCCEEDED, opRepo.getByKey("toggle-key-1")?.status)
    }

    @Test
    fun `cancellation during execution marks ledger FAILED and rethrows`() = runTest {
        val registry = ToolRegistry()
        val opRepo = FakeOperationRepository()

        registry.register(
            createTestTool(name = "long_job", tier = PermissionTier.REVERSIBLE_WRITE) {
                throw CancellationException("Cancelled")
            },
        )

        val executor = ToolExecutor(
            registry = registry,
            audit = AuditLogger { },
            operationRepository = opRepo,
        )

        try {
            executor.execute(
                toolName = "long_job",
                argsJson = "{}",
                idempotencyKey = "job-cancel-key",
            )
        } catch (_: CancellationException) {
            // Expected
        }

        val record = opRepo.getByKey("job-cancel-key")
        assertNotNull(record)
        assertEquals(OperationStatus.FAILED, record?.status)
        assertEquals("Operation cancelled", record?.errorMessage)
    }
}
