package com.jarvis.core.database.repository

import com.jarvis.core.common.ReversibleAction
import com.jarvis.core.database.dao.ReversibleActionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface ReversibleActionRepository {
    fun observeRecent(limit: Int = 20): Flow<List<ReversibleAction>>
    suspend fun get(id: String): ReversibleAction?
    suspend fun recordAction(action: ReversibleAction)
    suspend fun markReverted(id: String)
}

@Singleton
class RoomReversibleActionRepository @Inject constructor(
    private val dao: ReversibleActionDao,
) : ReversibleActionRepository {
    override fun observeRecent(limit: Int): Flow<List<ReversibleAction>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): ReversibleAction? =
        dao.get(id)?.toDomain()

    override suspend fun recordAction(action: ReversibleAction) =
        dao.insert(action.toEntity())

    override suspend fun markReverted(id: String) =
        dao.markReverted(id)
}
