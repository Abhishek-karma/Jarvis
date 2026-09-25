package com.jarvis.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
    suspend fun setPinned(
        id: String,
        pinned: Boolean,
        nowMs: Long,
    )

    @Query("UPDATE conversations SET title = :title, updatedAt = :nowMs WHERE id = :id")
    suspend fun rename(
        id: String,
        title: String,
        nowMs: Long,
    )

    @Query(
        "DELETE FROM conversations WHERE title = :defaultTitle AND " +
            "(SELECT COUNT(*) FROM messages WHERE messages.conversationId = conversations.id) = 0",
    )
    suspend fun deleteEmptyByTitle(defaultTitle: String): Int

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
    suspend fun deleteAfter(
        conversationId: String,
        fromCreatedAt: Long,
    )

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


@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AuditLogEntity)
}

@Dao
interface OperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OperationEntity)

    @Query("SELECT * FROM operations WHERE idempotencyKey = :key")
    suspend fun getByKey(key: String): OperationEntity?

    @Query("UPDATE operations SET status = :status, resultJson = :resultJson, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        resultJson: String?,
        errorMessage: String?,
        updatedAt: Long,
    )

    @Query("SELECT * FROM operations WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun listForTask(taskId: String): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE status = 'EXECUTING'")
    suspend fun listExecuting(): List<OperationEntity>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE isActive = 1 ORDER BY timestamp DESC")
    fun observeActive(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE isActive = 1 AND category = :category ORDER BY timestamp DESC")
    fun observeByCategory(category: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE isActive = 1 ORDER BY timestamp DESC")
    suspend fun getActive(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE isActive = 1 AND isPrivate = 0 ORDER BY timestamp DESC")
    suspend fun getActiveNonPrivate(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("UPDATE memories SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memories WHERE source = :source")
    suspend fun deleteBySource(source: String): Int

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE state = :state ORDER BY createdAt DESC")
    fun observeByState(state: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE state IN ('SCHEDULED', 'QUEUED', 'RUNNING', 'WAITING_FOR_CONFIRMATION')")
    suspend fun getActiveOrPendingTasks(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET state = :state, failureReason = :failureReason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, failureReason: String?, updatedAt: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE enabled = 1 ORDER BY nextRunAt ASC")
    fun observeEnabled(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun get(id: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: RoutineEntity)

    @Query("UPDATE routines SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReversibleActionDao {
    @Query("SELECT * FROM reversible_actions WHERE isReverted = 0 ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ReversibleActionEntity>>

    @Query("SELECT * FROM reversible_actions WHERE id = :id")
    suspend fun get(id: String): ReversibleActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: ReversibleActionEntity)

    @Query("UPDATE reversible_actions SET isReverted = 1 WHERE id = :id")
    suspend fun markReverted(id: String)
}

@Dao
interface RequestDiagnosticsDao {
    @Query("SELECT * FROM request_diagnostics ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<RequestDiagnosticsEntity>>

    @Query("SELECT * FROM request_diagnostics ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<RequestDiagnosticsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RequestDiagnosticsEntity)

    @Query("DELETE FROM request_diagnostics")
    suspend fun clearAll()
}

@Dao
interface ToolCatalogDao {
    @Query("SELECT * FROM tool_catalog ORDER BY source ASC, name ASC")
    fun observeAll(): Flow<List<ToolCatalogEntity>>

    @Query("SELECT * FROM tool_catalog WHERE enabled = 1 ORDER BY name ASC")
    fun observeEnabled(): Flow<List<ToolCatalogEntity>>

    @Query("SELECT * FROM tool_catalog ORDER BY source ASC, name ASC")
    suspend fun getAll(): List<ToolCatalogEntity>

    @Query("SELECT * FROM tool_catalog WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<ToolCatalogEntity>

    @Query("SELECT * FROM tool_catalog WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ToolCatalogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ToolCatalogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ToolCatalogEntity>)

    @Query("UPDATE tool_catalog SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM tool_catalog WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tool_catalog WHERE source = :source")
    suspend fun deleteBySource(source: String)
}

