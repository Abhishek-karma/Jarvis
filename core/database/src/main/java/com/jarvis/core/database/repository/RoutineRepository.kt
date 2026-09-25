package com.jarvis.core.database.repository

import com.jarvis.core.common.Routine
import com.jarvis.core.database.dao.RoutineDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

typealias RoomRoutineRepository = RoutineRepository

@Singleton
open class RoutineRepository @Inject constructor(
    private val routineDao: RoutineDao? = null,
) {
    open fun observeAll(): Flow<List<Routine>> =
        routineDao?.observeAll()?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open fun observeEnabled(): Flow<List<Routine>> =
        routineDao?.observeEnabled()?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open suspend fun get(id: String): Routine? =
        routineDao?.get(id)?.toDomain()

    open suspend fun upsert(routine: Routine) {
        routineDao?.upsert(routine.toEntity())
    }

    open suspend fun setEnabled(id: String, enabled: Boolean) {
        routineDao?.setEnabled(id, enabled, System.currentTimeMillis())
    }

    open suspend fun delete(id: String) {
        routineDao?.delete(id)
    }
}
