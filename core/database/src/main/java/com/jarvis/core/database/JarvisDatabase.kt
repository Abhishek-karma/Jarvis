package com.jarvis.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jarvis.core.database.dao.AuditLogDao
import com.jarvis.core.database.dao.ConversationDao
import com.jarvis.core.database.dao.MessageDao
import com.jarvis.core.database.dao.ProviderDao
import com.jarvis.core.database.entity.AuditLogEntity
import com.jarvis.core.database.entity.ConversationEntity
import com.jarvis.core.database.entity.MessageEntity
import com.jarvis.core.database.entity.ProviderEntity
import com.jarvis.core.database.repository.MIGRATION_1_2
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
    ],
    version = 2,
    exportSchema = true,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
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
}
