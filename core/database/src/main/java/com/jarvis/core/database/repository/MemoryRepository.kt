package com.jarvis.core.database.repository

import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.database.dao.MemoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

typealias RoomMemoryRepository = MemoryRepository

@Singleton
open class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao? = null,
) {
    open fun observeActive(): Flow<List<Memory>> =
        memoryDao?.observeActive()?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open fun observeByCategory(category: MemoryCategory): Flow<List<Memory>> =
        memoryDao?.observeByCategory(category.name)?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open suspend fun getActive(): List<Memory> =
        memoryDao?.getActive()?.map { it.toDomain() } ?: emptyList()

    open suspend fun getActiveNonPrivate(): List<Memory> =
        memoryDao?.getActiveNonPrivate()?.map { it.toDomain() } ?: emptyList()

    open suspend fun get(id: String): Memory? =
        memoryDao?.get(id)?.toDomain()

    open suspend fun upsert(memory: Memory) {
        memoryDao?.upsert(memory.toEntity())
    }

    open suspend fun setActive(id: String, isActive: Boolean) {
        memoryDao?.setActive(id, isActive)
    }

    open suspend fun delete(id: String) {
        memoryDao?.delete(id)
    }

    open suspend fun deleteBySource(source: String): Int =
        memoryDao?.deleteBySource(source) ?: 0

    open suspend fun clearAll() {
        memoryDao?.clearAll()
    }
}
