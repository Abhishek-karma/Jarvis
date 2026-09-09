package com.jarvis.core.database.repository

import com.jarvis.core.common.Routine
import com.jarvis.core.database.dao.RoutineDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface RoutineRepository {
    fun observeAll(): Flow<List<Routine>>
    fun observeEnabled(): Flow<List<Routine>>
    suspend fun get(id: String): Routine?
    suspend fun upsert(routine: Routine)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
}

@Singleton
class RoomRoutineRepository @Inject constructor(
    private val routineDao: RoutineDao,
) : RoutineRepository {
    override fun observeAll(): Flow<List<Routine>> =
        routineDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEnabled(): Flow<List<Routine>> =
        routineDao.observeEnabled().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Routine? =
        routineDao.get(id)?.toDomain()

    override suspend fun upsert(routine: Routine) =
        routineDao.upsert(routine.toEntity())

    override suspend fun setEnabled(id: String, enabled: Boolean) =
        routineDao.setEnabled(id, enabled, System.currentTimeMillis())

    override suspend fun delete(id: String) =
        routineDao.delete(id)
}
