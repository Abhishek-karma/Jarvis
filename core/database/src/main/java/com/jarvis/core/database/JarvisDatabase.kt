package com.jarvis.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.core.database.dao.AuditLogDao
import com.jarvis.core.database.dao.ConversationDao
import com.jarvis.core.database.dao.MemoryDao
import com.jarvis.core.database.dao.MessageDao
import com.jarvis.core.database.dao.OperationDao
import com.jarvis.core.database.dao.ProviderDao
import com.jarvis.core.database.dao.RequestDiagnosticsDao
import com.jarvis.core.database.dao.ReversibleActionDao
import com.jarvis.core.database.dao.RoutineDao
import com.jarvis.core.database.dao.TaskDao
import com.jarvis.core.database.dao.ToolCatalogDao
import com.jarvis.core.database.entity.AuditLogEntity
import com.jarvis.core.database.entity.ConversationEntity
import com.jarvis.core.database.entity.MemoryEntity
import com.jarvis.core.database.entity.MessageEntity
import com.jarvis.core.database.entity.OperationEntity
import com.jarvis.core.database.entity.ProviderEntity
import com.jarvis.core.database.entity.RequestDiagnosticsEntity
import com.jarvis.core.database.entity.ReversibleActionEntity
import com.jarvis.core.database.entity.RoutineEntity
import com.jarvis.core.database.entity.TaskEntity
import com.jarvis.core.database.entity.ToolCatalogEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `audit_log` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `agentRunId` TEXT,
                `toolName` TEXT NOT NULL,
                `tier` TEXT NOT NULL,
                `paramsRedactedJson` TEXT NOT NULL,
                `resultStatus` TEXT NOT NULL,
                `userConfirmed` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `model` TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memories` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `category` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `isPrivate` INTEGER NOT NULL,
                `isActive` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_category` ON `memories` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_timestamp` ON `memories` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_isPrivate` ON `memories` (`isPrivate`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tasks` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `goal` TEXT NOT NULL,
                `triggerType` TEXT NOT NULL,
                `triggerConfigJson` TEXT,
                `state` TEXT NOT NULL,
                `stepsJson` TEXT NOT NULL,
                `requiredPermissionsJson` TEXT NOT NULL,
                `retries` INTEGER NOT NULL,
                `maxRetries` INTEGER NOT NULL,
                `resultJson` TEXT,
                `failureReason` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `lastRunAt` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_state` ON `tasks` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_createdAt` ON `tasks` (`createdAt`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routines` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `goal` TEXT NOT NULL,
                `scheduleType` TEXT NOT NULL,
                `cronOrInterval` TEXT NOT NULL,
                `nextRunAt` INTEGER,
                `enabled` INTEGER NOT NULL,
                `lastRunAt` INTEGER,
                `lastRunStatus` TEXT,
                `failureReason` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_enabled` ON `routines` (`enabled`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_nextRunAt` ON `routines` (`nextRunAt`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reversible_actions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `actionType` TEXT NOT NULL,
                `target` TEXT NOT NULL,
                `inverseActionJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `isReverted` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reversible_actions_createdAt` ON `reversible_actions` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reversible_actions_isReverted` ON `reversible_actions` (`isReverted`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `request_diagnostics` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `requestId` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `providerId` TEXT NOT NULL,
                `model` TEXT NOT NULL,
                `route` TEXT NOT NULL,
                `latencyMs` INTEGER NOT NULL,
                `firstTokenLatencyMs` INTEGER,
                `promptTokens` INTEGER NOT NULL,
                `completionTokens` INTEGER NOT NULL,
                `totalTokens` INTEGER NOT NULL,
                `retries` INTEGER NOT NULL,
                `fallbacks` TEXT NOT NULL,
                `toolCount` INTEGER NOT NULL,
                `failureClass` TEXT,
                `errorMessage` TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_diagnostics_timestamp` ON `request_diagnostics` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_diagnostics_providerId` ON `request_diagnostics` (`providerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_diagnostics_route` ON `request_diagnostics` (`route`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `operations` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `taskId` TEXT NOT NULL,
                `toolName` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `resultJson` TEXT,
                `errorMessage` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_taskId` ON `operations` (`taskId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_operations_idempotencyKey` ON `operations` (`idempotencyKey`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tool_catalog` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `parametersSchemaJson` TEXT NOT NULL,
                `tier` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `version` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `configJson` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_catalog_source` ON `tool_catalog` (`source`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_catalog_enabled` ON `tool_catalog` (`enabled`)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `operations` WHERE `taskId` NOT IN (SELECT `id` FROM `tasks`)")
        db.execSQL(
            """
            CREATE TABLE `operations_new` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `taskId` TEXT NOT NULL,
                `toolName` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `resultJson` TEXT,
                `errorMessage` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `operations_new` (`id`, `taskId`, `toolName`, `idempotencyKey`, `status`, `resultJson`, `errorMessage`, `createdAt`, `updatedAt`)
            SELECT `id`, `taskId`, `toolName`, `idempotencyKey`, `status`, `resultJson`, `errorMessage`, `createdAt`, `updatedAt` FROM `operations`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `operations`")
        db.execSQL("ALTER TABLE `operations_new` RENAME TO `operations`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_taskId` ON `operations` (`taskId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_operations_idempotencyKey` ON `operations` (`idempotencyKey`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `messages` (`conversationId`, `createdAt`)")
    }
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
        AuditLogEntity::class,
        MemoryEntity::class,
        TaskEntity::class,
        RoutineEntity::class,
        ReversibleActionEntity::class,
        RequestDiagnosticsEntity::class,
        OperationEntity::class,
        ToolCatalogEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao
    abstract fun routineDao(): RoutineDao
    abstract fun reversibleActionDao(): ReversibleActionDao
    abstract fun requestDiagnosticsDao(): RequestDiagnosticsDao
    abstract fun operationDao(): OperationDao
    abstract fun toolCatalogDao(): ToolCatalogDao

    companion object {
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db")
            .addMigrations(*JarvisDatabase.ALL_MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun provideConversationDao(db: JarvisDatabase): ConversationDao = db.conversationDao()

    @Provides
    @Singleton
    fun provideMessageDao(db: JarvisDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideProviderDao(db: JarvisDatabase): ProviderDao = db.providerDao()

    @Provides
    @Singleton
    fun provideAuditLogDao(db: JarvisDatabase): AuditLogDao = db.auditLogDao()

    @Provides
    @Singleton
    fun provideMemoryDao(db: JarvisDatabase): MemoryDao = db.memoryDao()

    @Provides
    @Singleton
    fun provideTaskDao(db: JarvisDatabase): TaskDao = db.taskDao()

    @Provides
    @Singleton
    fun provideRoutineDao(db: JarvisDatabase): RoutineDao = db.routineDao()

    @Provides
    @Singleton
    fun provideReversibleActionDao(db: JarvisDatabase): ReversibleActionDao = db.reversibleActionDao()

    @Provides
    @Singleton
    fun provideRequestDiagnosticsDao(db: JarvisDatabase): RequestDiagnosticsDao = db.requestDiagnosticsDao()

    @Provides
    @Singleton
    fun provideOperationDao(db: JarvisDatabase): OperationDao = db.operationDao()

    @Provides
    @Singleton
    fun provideToolCatalogDao(db: JarvisDatabase): ToolCatalogDao = db.toolCatalogDao()

    @Provides
    @Singleton
    fun provideConversationRepository(conversationDao: ConversationDao, messageDao: MessageDao): com.jarvis.core.database.repository.ConversationRepository =
        com.jarvis.core.database.repository.ConversationRepository(conversationDao, messageDao)

    @Provides
    @Singleton
    fun provideAuditLogRepository(auditLogDao: AuditLogDao): com.jarvis.core.database.repository.AuditLogRepository =
        com.jarvis.core.database.repository.AuditLogRepository(auditLogDao)

    @Provides
    @Singleton
    fun provideMemoryRepository(memoryDao: MemoryDao): com.jarvis.core.database.repository.MemoryRepository =
        com.jarvis.core.database.repository.MemoryRepository(memoryDao)

    @Provides
    @Singleton
    fun provideTaskRepository(taskDao: TaskDao): com.jarvis.core.database.repository.TaskRepository =
        com.jarvis.core.database.repository.TaskRepository(taskDao)

    @Provides
    @Singleton
    fun provideRoutineRepository(routineDao: RoutineDao): com.jarvis.core.database.repository.RoutineRepository =
        com.jarvis.core.database.repository.RoutineRepository(routineDao)

    @Provides
    @Singleton
    fun provideReversibleActionRepository(dao: ReversibleActionDao): com.jarvis.core.database.repository.ReversibleActionRepository =
        com.jarvis.core.database.repository.ReversibleActionRepository(dao)

    @Provides
    @Singleton
    fun provideOperationRepository(dao: OperationDao): com.jarvis.core.database.repository.OperationRepository =
        com.jarvis.core.database.repository.OperationRepository(dao)

    @Provides
    @Singleton
    fun provideToolCatalogRepository(dao: ToolCatalogDao): com.jarvis.core.database.repository.ToolCatalogRepository =
        com.jarvis.core.database.repository.ToolCatalogRepository(dao)
}
