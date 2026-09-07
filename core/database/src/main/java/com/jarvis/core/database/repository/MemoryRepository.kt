package com.jarvis.core.database.repository

import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.database.dao.MemoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface MemoryRepository {
    fun observeActive(): Flow<List<Memory>>
    fun observeByCategory(category: MemoryCategory): Flow<List<Memory>>
    suspend fun getActive(): List<Memory>
    suspend fun getActiveNonPrivate(): List<Memory>
    suspend fun get(id: String): Memory?
    suspend fun upsert(memory: Memory)
    suspend fun setActive(id: String, isActive: Boolean)
    suspend fun delete(id: String)
    suspend fun deleteBySource(source: String): Int
    suspend fun clearAll()
}

@Singleton
class RoomMemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
) : MemoryRepository {
    override fun observeActive(): Flow<List<Memory>> =
        memoryDao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observeByCategory(category: MemoryCategory): Flow<List<Memory>> =
        memoryDao.observeByCategory(category.name).map { list -> list.map { it.toDomain() } }

    override suspend fun getActive(): List<Memory> =
        memoryDao.getActive().map { it.toDomain() }

    override suspend fun getActiveNonPrivate(): List<Memory> =
        memoryDao.getActiveNonPrivate().map { it.toDomain() }

    override suspend fun get(id: String): Memory? =
        memoryDao.get(id)?.toDomain()

    override suspend fun upsert(memory: Memory) =
        memoryDao.upsert(memory.toEntity())

    override suspend fun setActive(id: String, isActive: Boolean) =
        memoryDao.setActive(id, isActive)

    override suspend fun delete(id: String) =
        memoryDao.delete(id)

    override suspend fun deleteBySource(source: String): Int =
        memoryDao.deleteBySource(source)

    override suspend fun clearAll() =
        memoryDao.clearAll()
}
