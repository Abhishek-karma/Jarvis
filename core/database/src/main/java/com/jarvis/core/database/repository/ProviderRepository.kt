package com.jarvis.core.database.repository

import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.database.dao.ProviderDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persists provider configurations; API keys are deliberately NOT stored here. */
@Singleton
class ProviderRepository @Inject constructor(
    private val providerDao: ProviderDao,
) {
    fun observeProviders(): Flow<List<ProviderConfig>> =
        providerDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getProvider(id: String): ProviderConfig? = providerDao.get(id)?.toDomain()

    suspend fun upsert(provider: ProviderConfig) = providerDao.upsert(provider.toEntity())

    suspend fun delete(id: String) = providerDao.delete(id)

    suspend fun setDefault(id: String) = providerDao.setDefault(id)
}
