package com.jarvis.core.database

import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.database.dao.OperationDao
import com.jarvis.core.database.entity.OperationEntity
import com.jarvis.core.database.repository.RoomOperationRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OperationRepositoryTest {

    private val fakeDao = object : OperationDao {
        val store = mutableListOf<OperationEntity>()

        override suspend fun insert(entry: OperationEntity) {
            if (store.any { it.idempotencyKey == entry.idempotencyKey }) {
                throw IllegalStateException("UNIQUE constraint failed: operations.idempotencyKey")
            }
            store.add(entry)
        }

        override suspend fun getByKey(key: String): OperationEntity? =
            store.find { it.idempotencyKey == key }

        override suspend fun updateStatus(
            id: String, status: String, resultJson: String?,
            errorMessage: String?, updatedAt: Long,
        ) {
            val idx = store.indexOfFirst { it.id == id }
            if (idx >= 0) {
                store[idx] = store[idx].copy(
                    status = status, resultJson = resultJson,
                    errorMessage = errorMessage, updatedAt = updatedAt,
                )
            }
        }

        override suspend fun listForTask(taskId: String): List<OperationEntity> =
            store.filter { it.taskId == taskId }

        override suspend fun listExecuting(): List<OperationEntity> =
            store.filter { it.status == OperationStatus.EXECUTING.name }
    }

    private lateinit var repo: RoomOperationRepository

    @BeforeEach
    fun setUp() {
        fakeDao.store.clear()
        repo = RoomOperationRepository(fakeDao)
    }

    private fun op(
        id: String = "op-1",
        key: String = "idem-key-1",
        status: OperationStatus = OperationStatus.EXECUTING,
    ) = Operation(
        id = id, taskId = "task-1", toolName = "set_alarm",
        idempotencyKey = key, status = status,
    )

    @Test
    fun `insert and retrieve by idempotency key`() = runTest {
        repo.insert(op())
        val found = repo.getByKey("idem-key-1")
        assertNotNull(found)
        assertEquals("op-1", found!!.id)
        assertEquals(OperationStatus.EXECUTING, found.status)
    }

    @Test
    fun `getByKey returns null for missing key`() = runTest {
        assertNull(repo.getByKey("nonexistent"))
    }

    @Test
    fun `duplicate idempotency key throws, preserving first record`() = runTest {
        repo.insert(op())
        val ex = runCatching { repo.insert(op(id = "op-2", key = "idem-key-1")) }
        assert(ex.isFailure) { "Expected duplicate key insertion to throw" }
        assertEquals("op-1", repo.getByKey("idem-key-1")!!.id)
    }

    @Test
    fun `updateStatus transitions EXECUTING to SUCCEEDED`() = runTest {
        val original = op()
        repo.insert(original)
        repo.updateStatus(original.copy(
            status = OperationStatus.SUCCEEDED,
            resultJson = """{"alarm_id": 42}""",
            updatedAt = 9999L,
        ))
        val updated = repo.getByKey("idem-key-1")!!
        assertEquals(OperationStatus.SUCCEEDED, updated.status)
        assertEquals("""{"alarm_id": 42}""", updated.resultJson)
    }

    @Test
    fun `updateStatus transitions EXECUTING to FAILED with error message`() = runTest {
        val original = op()
        repo.insert(original)
        repo.updateStatus(original.copy(
            status = OperationStatus.FAILED,
            errorMessage = "Permission denied",
            updatedAt = 9999L,
        ))
        val updated = repo.getByKey("idem-key-1")!!
        assertEquals(OperationStatus.FAILED, updated.status)
        assertEquals("Permission denied", updated.errorMessage)
    }

    @Test
    fun `listForTask returns all operations for a given task`() = runTest {
        repo.insert(op(id = "op-1", key = "k1"))
        repo.insert(op(id = "op-2", key = "k2"))
        repo.insert(Operation(
            id = "op-3", taskId = "other-task", toolName = "x",
            idempotencyKey = "k3", status = OperationStatus.EXECUTING,
        ))
        val ops = repo.listForTask("task-1")
        assertEquals(2, ops.size)
        assertEquals(setOf("op-1", "op-2"), ops.map { it.id }.toSet())
    }
}
