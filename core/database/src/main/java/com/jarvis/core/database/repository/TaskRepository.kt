package com.jarvis.core.database.repository

import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.dao.TaskDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface TaskRepository {
    fun observeAll(): Flow<List<Task>>
    fun observeByState(state: TaskState): Flow<List<Task>>
    suspend fun get(id: String): Task?
    suspend fun getActiveOrPendingTasks(): List<Task>
    suspend fun upsert(task: Task)
    suspend fun updateState(id: String, state: TaskState, failureReason: String? = null)
    suspend fun delete(id: String)
}

@Singleton
class RoomTaskRepository @Inject constructor(
    private val taskDao: TaskDao,
) : TaskRepository {
    override fun observeAll(): Flow<List<Task>> =
        taskDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByState(state: TaskState): Flow<List<Task>> =
        taskDao.observeByState(state.name).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Task? =
        taskDao.get(id)?.toDomain()

    override suspend fun getActiveOrPendingTasks(): List<Task> =
        taskDao.getActiveOrPendingTasks().map { it.toDomain() }

    override suspend fun upsert(task: Task) =
        taskDao.upsert(task.toEntity())

    override suspend fun updateState(id: String, state: TaskState, failureReason: String?) =
        taskDao.updateState(
            id = id,
            state = state.name,
            failureReason = failureReason,
            updatedAt = System.currentTimeMillis(),
        )

    override suspend fun delete(id: String) =
        taskDao.delete(id)
}
