package com.jarvis.core.database.repository

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.core.database.dao.AuditLogDao
import com.jarvis.core.database.entity.AuditLogEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Domain record for one audit row (14-SECURITY.md §7) — params arrive pre-redacted. */
data class AuditLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val agentRunId: String?,
    val toolName: String,
    val tier: String,
    val paramsRedactedJson: String,
    val resultStatus: String,
    val userConfirmed: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Storage contract for the audit log; Room-backed impl is append-only at the DAO level. */
interface AuditLogRepository {
    suspend fun record(entry: AuditLogEntry)
}

@Singleton
class RoomAuditLogRepository @Inject constructor(
    private val auditLogDao: AuditLogDao,
) : AuditLogRepository {
    override suspend fun record(entry: AuditLogEntry) = auditLogDao.insert(
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

/** v1 → v2: adds the append-only audit_log table (09-DATA-MODELS.md §2). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `audit_log` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, " +
                "`agentRunId` TEXT, " +
                "`toolName` TEXT NOT NULL, " +
                "`tier` TEXT NOT NULL, " +
                "`paramsRedactedJson` TEXT NOT NULL, " +
                "`resultStatus` TEXT NOT NULL, " +
                "`userConfirmed` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL" +
                ")",
        )
    }
}
