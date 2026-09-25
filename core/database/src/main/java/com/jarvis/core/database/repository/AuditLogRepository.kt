package com.jarvis.core.database.repository

import com.jarvis.core.database.dao.AuditLogDao
import com.jarvis.core.database.entity.AuditLogEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audit log entry recorded for every tool execution attempt.
 */
data class AuditLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val agentRunId: String? = null,
    val toolName: String,
    val tier: String,
    val paramsRedactedJson: String,
    val resultStatus: String,
    val userConfirmed: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

typealias RoomAuditLogRepository = AuditLogRepository

/** Storage repository for the audit log. */
@Singleton
open class AuditLogRepository @Inject constructor(
    private val auditLogDao: AuditLogDao? = null,
) {
    open suspend fun record(entry: AuditLogEntry) {
        auditLogDao?.insert(
            AuditLogEntity(
                id = entry.id,
                agentRunId = entry.agentRunId,
                toolName = entry.toolName,
                tier = entry.tier,
                paramsRedactedJson = entry.paramsRedactedJson,
                resultStatus = entry.resultStatus,
                userConfirmed = entry.userConfirmed,
                timestamp = entry.timestamp,
            ),
        )
    }
}
