package com.jarvis.core.database.repository

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.core.database.dao.AuditLogDao
import com.jarvis.core.database.entity.AuditLogEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Domain record for one audit row — params arrive pre-redacted. */
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

/** v1 → v2: adds the append-only audit_log table. */
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

/** v2 → v3: providers gain an optional default-model column for chat requests. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `model` TEXT")
    }
}

/** v3 → v4: Phase 2 assistant platform tables (memories, tasks, routines, reversible_actions). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memories` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, " +
                "`category` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`confidence` REAL NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`isPrivate` INTEGER NOT NULL, " +
                "`isActive` INTEGER NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_category` ON `memories` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_timestamp` ON `memories` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_isPrivate` ON `memories` (`isPrivate`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tasks` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, " +
                "`title` TEXT NOT NULL, " +
                "`goal` TEXT NOT NULL, " +
                "`triggerType` TEXT NOT NULL, " +
                "`triggerConfigJson` TEXT, " +
                "`state` TEXT NOT NULL, " +
                "`stepsJson` TEXT NOT NULL, " +
                "`requiredPermissionsJson` TEXT NOT NULL, " +
                "`retries` INTEGER NOT NULL, " +
                "`maxRetries` INTEGER NOT NULL, " +
                "`resultJson` TEXT, " +
                "`failureReason` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`lastRunAt` INTEGER" +
                ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_state` ON `tasks` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_createdAt` ON `tasks` (`createdAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `routines` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, " +
                "`name` TEXT NOT NULL, " +
                "`goal` TEXT NOT NULL, " +
                "`scheduleType` TEXT NOT NULL, " +
                "`cronOrInterval` TEXT NOT NULL, " +
                "`nextRunAt` INTEGER, " +
                "`enabled` INTEGER NOT NULL, " +
                "`lastRunAt` INTEGER, " +
                "`lastRunStatus` TEXT, " +
                "`failureReason` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_enabled` ON `routines` (`enabled`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_nextRunAt` ON `routines` (`nextRunAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reversible_actions` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, " +
                "`actionType` TEXT NOT NULL, " +
                "`target` TEXT NOT NULL, " +
                "`inverseActionJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`isReverted` INTEGER NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reversible_actions_createdAt` ON `reversible_actions` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reversible_actions_isReverted` ON `reversible_actions` (`isReverted`)")
    }
}
