package com.jarvis.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.core.database.entity.AuditLogEntity
import com.jarvis.core.database.entity.ConversationEntity
import com.jarvis.core.database.entity.MessageEntity
import com.jarvis.core.database.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :nowMs WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, nowMs: Long)

    @Query("UPDATE conversations SET title = :title, updatedAt = :nowMs WHERE id = :id")
    suspend fun rename(id: String, title: String, nowMs: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt > :fromCreatedAt")
    suspend fun deleteAfter(conversationId: String, fromCreatedAt: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(conversationId: String): MessageEntity?
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE providers SET isDefault = CASE WHEN id = :providerId THEN 1 ELSE 0 END")
    suspend fun setDefault(providerId: String)
}

/**
 * Append-only audit log (09-DATA-MODELS.md §2): only inserts exist — no @Update or @Delete
 * methods, keeping the log tamper-evident within the app.
 */
@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AuditLogEntity)
}

