package com.jarvis.core.database

import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.database.dao.RequestDiagnosticsDao
import com.jarvis.core.database.entity.RequestDiagnosticsEntity
import com.jarvis.core.database.repository.DiagnosticsRepository
import com.jarvis.core.database.repository.RequestDiagnostics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsRepositoryTest {

    private val fakeDao = object : RequestDiagnosticsDao {
        val list = mutableListOf<RequestDiagnosticsEntity>()

        override fun observeRecent(limit: Int): Flow<List<RequestDiagnosticsEntity>> =
            flowOf(list.take(limit))

        override suspend fun getRecent(limit: Int): List<RequestDiagnosticsEntity> =
            list.take(limit)

        override suspend fun insert(entity: RequestDiagnosticsEntity) {
            list.add(0, entity)
        }

        override suspend fun clearAll() {
            list.clear()
        }
    }

    private lateinit var repository: DiagnosticsRepository

    @BeforeEach
    fun setUp() {
        fakeDao.list.clear()
        repository = DiagnosticsRepository(fakeDao, DispatcherProvider())
    }

    @Test
    fun `record inserts trace and retrieve returns mapped domain model`() = runTest {
        val trace = RequestDiagnostics(
            requestId = "req-123",
            providerId = "gemini",
            model = "gemini-2.5-flash",
            route = "cloud",
            latencyMs = 450L,
            promptTokens = 120,
            completionTokens = 85,
            totalTokens = 205,
        )

        repository.record(trace)
        val recent = repository.getRecent()

        assertEquals(1, recent.size)
        assertEquals("req-123", recent.first().requestId)
        assertEquals(450L, recent.first().latencyMs)
        assertEquals(205, recent.first().totalTokens)
    }

    @Test
    fun `generateSanitizedReport creates markdown without private contents`() = runTest {
        repository.record(
            RequestDiagnostics(
                requestId = "req-1",
                providerId = "openai",
                model = "gpt-4o-mini",
                route = "cloud",
                latencyMs = 600L,
                promptTokens = 50,
                completionTokens = 100,
                totalTokens = 150,
            ),
        )
        repository.record(
            RequestDiagnostics(
                requestId = "req-2",
                providerId = "local",
                model = "gemma-2b",
                route = "local",
                latencyMs = 200L,
                promptTokens = 40,
                completionTokens = 40,
                totalTokens = 80,
                failureClass = "OOM",
                errorMessage = "Out of memory",
            ),
        )

        val report = repository.generateSanitizedReport()

        assertTrue(report.contains("=== Jarvis Diagnostics Report ==="))
        assertTrue(report.contains("Total Traces Analyzed: 2"))
        assertTrue(report.contains("Failures Recorded: 1"))
        assertTrue(report.contains("Route: cloud | Provider: openai | Model: gpt-4o-mini"))
        assertTrue(report.contains("FAILURE: OOM - Out of memory"))
    }
}
