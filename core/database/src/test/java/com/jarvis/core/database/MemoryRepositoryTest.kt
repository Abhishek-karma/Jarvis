package com.jarvis.core.database

import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.database.dao.MemoryDao
import com.jarvis.core.database.entity.MemoryEntity
import com.jarvis.core.database.repository.RoomMemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MemoryRepositoryTest {

    private val fakeDao = object : MemoryDao {
        val store = mutableListOf<MemoryEntity>()

        override fun observeActive(): Flow<List<MemoryEntity>> =
            flowOf(store.filter { it.isActive })

        override fun observeByCategory(category: String): Flow<List<MemoryEntity>> =
            flowOf(store.filter { it.isActive && it.category == category })

        override suspend fun getActive(): List<MemoryEntity> =
            store.filter { it.isActive }

        override suspend fun getActiveNonPrivate(): List<MemoryEntity> =
            store.filter { it.isActive && !it.isPrivate }

        override suspend fun get(id: String): MemoryEntity? =
            store.find { it.id == id }

        override suspend fun upsert(memory: MemoryEntity) {
            val idx = store.indexOfFirst { it.id == memory.id }
            if (idx >= 0) store[idx] = memory else store.add(memory)
        }

        override suspend fun setActive(id: String, isActive: Boolean) {
            val idx = store.indexOfFirst { it.id == id }
            if (idx >= 0) store[idx] = store[idx].copy(isActive = isActive)
        }

        override suspend fun delete(id: String) {
            store.removeAll { it.id == id }
        }

        override suspend fun deleteBySource(source: String): Int {
            val count = store.count { it.source == source }
            store.removeAll { it.source == source }
            return count
        }

        override suspend fun clearAll() {
            store.clear()
        }
    }

    private lateinit var repo: RoomMemoryRepository

    @BeforeEach
    fun setUp() {
        fakeDao.store.clear()
        repo = RoomMemoryRepository(fakeDao)
    }

    @Test
    fun `upsert and retrieve memory by id`() = runTest {
        val mem = Memory(
            id = "mem-1",
            category = MemoryCategory.LONG_TERM_FACT,
            content = "User prefers dark mode",
            source = "conversation",
        )
        repo.upsert(mem)
        val found = repo.get("mem-1")
        assertNotNull(found)
        assertEquals("User prefers dark mode", found!!.content)
    }

    @Test
    fun `getActive returns only active memories`() = runTest {
        repo.upsert(Memory(id = "m1", category = MemoryCategory.LONG_TERM_FACT, content = "fact 1", source = "s", isActive = true))
        repo.upsert(Memory(id = "m2", category = MemoryCategory.LONG_TERM_FACT, content = "fact 2", source = "s", isActive = true))
        repo.setActive("m2", false)
        val active = repo.getActive()
        assertEquals(1, active.size)
        assertEquals("m1", active.first().id)
    }

    @Test
    fun `getActiveNonPrivate excludes private memories`() = runTest {
        repo.upsert(Memory(id = "m1", category = MemoryCategory.LONG_TERM_FACT, content = "public fact", source = "s", isPrivate = false))
        repo.upsert(Memory(id = "m2", category = MemoryCategory.LONG_TERM_FACT, content = "secret", source = "s", isPrivate = true))
        val results = repo.getActiveNonPrivate()
        assertEquals(1, results.size)
        assertEquals("public fact", results.first().content)
    }

    @Test
    fun `deleteBySource removes all memories from that source`() = runTest {
        repo.upsert(Memory(id = "m1", category = MemoryCategory.EPISODIC, content = "x", source = "conv-123"))
        repo.upsert(Memory(id = "m2", category = MemoryCategory.EPISODIC, content = "y", source = "conv-123"))
        repo.upsert(Memory(id = "m3", category = MemoryCategory.EPISODIC, content = "z", source = "conv-456"))
        val count = repo.deleteBySource("conv-123")
        assertEquals(2, count)
        assertNull(repo.get("m1"))
        assertNotNull(repo.get("m3"))
    }

    @Test
    fun `clearAll removes everything`() = runTest {
        repo.upsert(Memory(id = "m1", category = MemoryCategory.LONG_TERM_FACT, content = "x", source = "s"))
        repo.clearAll()
        assertEquals(0, repo.getActive().size)
    }
}
