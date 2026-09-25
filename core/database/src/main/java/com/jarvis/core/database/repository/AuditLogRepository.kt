package com.jarvis.core.database.repository

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Storage repository for the audit log; Room-backed impl is append-only at the DAO level. */
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

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `audit_log` (
                    `id` TEXT NOT NULL PRIMARY KEY,
                    `agentRunId` TEXT NOT NULL,
                    `toolName` TEXT NOT NULL,
                    `tier` TEXT NOT NULL,
                    `paramsRedactedJson` TEXT NOT NULL,
                    `resultStatus` TEXT NOT NULL,
                    `userConfirmed` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_agentRunId` ON `audit_log` (`agentRunId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_timestamp` ON `audit_log` (`timestamp`)")
        }
    }
