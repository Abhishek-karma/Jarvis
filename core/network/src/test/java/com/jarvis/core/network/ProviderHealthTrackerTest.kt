package com.jarvis.core.network

import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderHealthTrackerTest {

    private lateinit var tracker: ProviderHealthTracker

    @BeforeEach
    fun setUp() {
        tracker = ProviderHealthTracker()
    }

    @Test
    fun `new provider defaults to healthy`() {
        assertTrue(tracker.isHealthy("prov-1"))
        val health = tracker.getHealth("prov-1")
        assertEquals(0L, health.totalRequests)
        assertEquals(0, health.failureCount)
    }

    @Test
    fun `recordSuccess updates latency and keeps provider healthy`() {
        tracker.recordSuccess("prov-1", latencyMs = 120L)
        val health = tracker.getHealth("prov-1")

        assertTrue(health.isHealthy)
        assertEquals(1L, health.totalRequests)
        assertEquals(120L, health.averageLatencyMs)
        assertEquals(0, health.consecutiveFailures)
    }

    @Test
    fun `recordFailure triggers backoff and temporarily marks unavailable`() {
        val now = System.currentTimeMillis()
        tracker.recordFailure(
            providerId = "prov-1",
            errorKind = ProviderErrorKind.TIMEOUT,
            errorMessage = "Socket timeout",
            backoffMs = 5000L,
        )

        val health = tracker.getHealth("prov-1")
        assertEquals(1, health.failureCount)
        assertEquals(1, health.consecutiveFailures)
        assertTrue(health.isTemporarilyUnavailable(now + 1000L))
        assertFalse(tracker.isHealthy("prov-1", now + 1000L))

        // Recovers after backoff
        assertFalse(health.isTemporarilyUnavailable(now + 60000L))
        assertTrue(tracker.isHealthy("prov-1", now + 60000L))
    }

    @Test
    fun `rate limit marks provider rate limited with backoff`() {
        val now = System.currentTimeMillis()
        tracker.recordFailure(
            providerId = "prov-2",
            errorKind = ProviderErrorKind.RATE_LIMIT,
            errorMessage = "HTTP 429",
            backoffMs = 30000L,
        )

        val health = tracker.getHealth("prov-2")
        assertTrue(health.isRateLimited)
        assertTrue(health.isTemporarilyUnavailable(now + 1000L))
    }

    @Test
    fun `filterHealthy excludes providers currently in backoff`() {
        val prov1 = ProviderConfig(
            id = "p1",
            name = "Provider 1",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api1.com",
        )
        val prov2 = ProviderConfig(
            id = "p2",
            name = "Provider 2",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api2.com",
        )

        tracker.recordFailure("p1", ProviderErrorKind.PROVIDER_UNAVAILABLE, backoffMs = 60000L)
        tracker.recordSuccess("p2", latencyMs = 200L)

        val healthy = tracker.filterHealthy(listOf(prov1, prov2))
        assertEquals(1, healthy.size)
        assertEquals("p2", healthy.first().id)
    }
}
