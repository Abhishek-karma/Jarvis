package com.jarvis.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
import com.jarvis.core.database.repository.MIGRATION_1_2
import com.jarvis.core.database.repository.MIGRATION_2_3
import com.jarvis.core.database.repository.MIGRATION_3_4
import com.jarvis.core.database.repository.MIGRATION_4_5
import com.jarvis.core.database.repository.MIGRATION_5_6
import com.jarvis.core.database.repository.MIGRATION_6_7
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
    version = 7,
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
}
