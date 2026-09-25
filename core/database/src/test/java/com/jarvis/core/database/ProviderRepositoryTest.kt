package com.jarvis.core.database

import com.jarvis.core.common.DEFAULT_NARA_PROVIDER
import com.jarvis.core.database.dao.ProviderDao
import com.jarvis.core.database.entity.ProviderEntity
import com.jarvis.core.database.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderRepositoryTest {

    private val fakeDao = object : ProviderDao {
        val store = mutableListOf<ProviderEntity>()

        override fun observeAll(): Flow<List<ProviderEntity>> = flowOf(store)

        override suspend fun get(id: String): ProviderEntity? = store.find { it.id == id }

        override suspend fun upsert(provider: ProviderEntity) {
            val idx = store.indexOfFirst { it.id == provider.id }
            if (idx >= 0) store[idx] = provider else store.add(provider)
        }

        override suspend fun delete(id: String) {
            store.removeAll { it.id == id }
        }

        override suspend fun setDefault(id: String) {
            for (i in store.indices) {
                store[i] = store[i].copy(isDefault = store[i].id == id)
            }
        }
    }

    private val repository = ProviderRepository(fakeDao)

    @Test
    fun `ensureDefaultProvider seeds Nara as default provider when none exists`() = runTest {
        repository.ensureDefaultProvider()

        val provider = repository.getProvider("nara")
        assertNotNull(provider)
        assertEquals("nara", provider?.id)
        assertEquals("Nara", provider?.name)
        assertEquals("https://router.bynara.id/v1", provider?.baseUrl)
        assertEquals("laguna-s-2.1", provider?.model)
        assertTrue(provider?.isDefault == true)
    }
}
