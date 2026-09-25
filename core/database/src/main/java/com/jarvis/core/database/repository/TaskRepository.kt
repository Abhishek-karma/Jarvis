package com.jarvis.core.database.repository

import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.dao.TaskDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

typealias RoomTaskRepository = TaskRepository

@Singleton
open class TaskRepository @Inject constructor(
    private val taskDao: TaskDao? = null,
) {
    open fun observeAll(): Flow<List<Task>> =
        taskDao?.observeAll()?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open fun observeByState(state: TaskState): Flow<List<Task>> =
        taskDao?.observeByState(state.name)?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open suspend fun get(id: String): Task? =
        taskDao?.get(id)?.toDomain()

    open suspend fun getActiveOrPendingTasks(): List<Task> =
        taskDao?.getActiveOrPendingTasks()?.map { it.toDomain() } ?: emptyList()

    open suspend fun upsert(task: Task) {
        taskDao?.upsert(task.toEntity())
    }

    open suspend fun updateState(id: String, state: TaskState, failureReason: String? = null) {
        taskDao?.updateState(
            id = id,
            state = state.name,
            failureReason = failureReason,
            updatedAt = System.currentTimeMillis(),
        )
    }

    open suspend fun delete(id: String) {
        taskDao?.delete(id)
    }
}
