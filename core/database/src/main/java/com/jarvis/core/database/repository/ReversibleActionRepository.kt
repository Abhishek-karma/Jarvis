package com.jarvis.core.database.repository

import com.jarvis.core.common.ReversibleAction
import com.jarvis.core.database.dao.ReversibleActionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

typealias RoomReversibleActionRepository = ReversibleActionRepository

@Singleton
open class ReversibleActionRepository @Inject constructor(
    private val dao: ReversibleActionDao? = null,
) {
    open fun observeRecent(limit: Int = 20): Flow<List<ReversibleAction>> =
        dao?.observeRecent(limit)?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open suspend fun get(id: String): ReversibleAction? =
        dao?.get(id)?.toDomain()

    open suspend fun recordAction(action: ReversibleAction) {
        dao?.insert(action.toEntity())
    }

    open suspend fun markReverted(id: String) {
        dao?.markReverted(id)
    }
}
