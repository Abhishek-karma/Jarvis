package com.jarvis.core.database.repository

import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.database.dao.OperationDao
import com.jarvis.core.database.entity.OperationEntity
import javax.inject.Inject
import javax.inject.Singleton

typealias RoomOperationRepository = OperationRepository

/**
 * Durable record of side effects, keyed by their idempotency key for persistent operation state and retry deduplication.
 */
@Singleton
open class OperationRepository @Inject constructor(
    private val operationDao: OperationDao? = null,
) {
    open suspend fun getByKey(key: String): Operation? = requireNotNull(operationDao) {
        "OperationRepository requires a persistent OperationDao"
    }.getByKey(key)?.toDomain()

    open suspend fun insert(operation: Operation) {
        requireNotNull(operationDao) {
            "OperationRepository requires a persistent OperationDao"
        }.insert(operation.toEntity())
    }

    open suspend fun updateStatus(operation: Operation) {
        requireNotNull(operationDao) {
            "OperationRepository requires a persistent OperationDao"
        }.updateStatus(
            id = operation.id,
            status = operation.status.name,
            resultJson = operation.resultJson,
            errorMessage = operation.errorMessage,
            updatedAt = operation.updatedAt,
        )
    }

    open suspend fun listForTask(taskId: String): List<Operation> =
        requireNotNull(operationDao) {
            "OperationRepository requires a persistent OperationDao"
        }.listForTask(taskId).map { it.toDomain() }

    open suspend fun listExecuting(): List<Operation> =
        requireNotNull(operationDao) {
            "OperationRepository requires a persistent OperationDao"
        }.listExecuting().map { it.toDomain() }
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
