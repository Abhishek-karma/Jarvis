package com.jarvis.core.database.repository

import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.database.dao.OperationDao
import com.jarvis.core.database.entity.OperationEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable record of side effects, keyed by their idempotency key. Replaces the
 * previous in-memory map + `Task.stepsJson.contains(...)` approach, which could
 * miss a duplicate after a process crash and which mis-matched partial JSON
 * fragments.
 */
interface OperationRepository {
    suspend fun getByKey(key: String): Operation?
    suspend fun insert(operation: Operation)
    suspend fun updateStatus(operation: Operation)
    suspend fun listForTask(taskId: String): List<Operation>
}

@Singleton
class RoomOperationRepository @Inject constructor(
    private val operationDao: OperationDao,
) : OperationRepository {

    override suspend fun getByKey(key: String): Operation? = operationDao.getByKey(key)?.toDomain()

    override suspend fun insert(operation: Operation) {
        operationDao.insert(operation.toEntity())
    }

    override suspend fun updateStatus(operation: Operation) {
        operationDao.updateStatus(
            id = operation.id,
            status = operation.status.name,
            resultJson = operation.resultJson,
            errorMessage = operation.errorMessage,
            updatedAt = operation.updatedAt,
        )
    }

    override suspend fun listForTask(taskId: String): List<Operation> =
        operationDao.listForTask(taskId).map { it.toDomain() }
}

private fun OperationEntity.toDomain(): Operation = Operation(
    id = id,
    taskId = taskId,
    toolName = toolName,
    idempotencyKey = idempotencyKey,
    status = OperationStatus.fromString(status),
    resultJson = resultJson,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Operation.toEntity(): OperationEntity = OperationEntity(
    id = id,
    taskId = taskId,
    toolName = toolName,
    idempotencyKey = idempotencyKey,
    status = status.name,
    resultJson = resultJson,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
