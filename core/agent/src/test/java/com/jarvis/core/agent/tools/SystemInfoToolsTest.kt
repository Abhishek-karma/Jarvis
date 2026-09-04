package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemInfoToolsTest {

    @Test
    fun `battery level returns percentage as observation`() = runBlocking {
        val tool = SystemInfoTools.batteryLevel { 76 }

        val result = tool.execute("{}")

        assertTrue(result.success)
        assertTrue(result.observationText.contains("76"))
        assertEquals(76, result.structuredData?.get("percent"))
    }

    @Test
    fun `battery level reports failure when the platform cannot read it`() = runBlocking {
        val tool = SystemInfoTools.batteryLevel { null }

        val result = tool.execute("{}")

        assertFalse(result.success)
        assertTrue(result.error != null)
    }

    @Test
    fun `storage free reports bytes`() = runBlocking {
        val tool = SystemInfoTools.storageFree { 5_368_709_120L }

        val result = tool.execute("{}")

        assertTrue(result.success)
        assertTrue(result.observationText.contains("5368709120"))
        assertEquals(5_368_709_120L, result.structuredData?.get("freeBytes"))
    }

    @Test
    fun `network status reports wifi`() = runBlocking {
        val tool = SystemInfoTools.networkStatus { "wifi" }

        val result = tool.execute("{}")

        assertTrue(result.success)
        assertTrue(result.observationText.contains("wifi"))
        assertEquals("wifi", result.structuredData?.get("state"))
    }

    @Test
    fun `current time reports the injected clock in UTC ISO-8601`() = runBlocking {
        val tool = SystemInfoTools.currentTime(nowUtcMillis = { 1_700_000_000_000L })

        val result = tool.execute("{}")

        assertTrue(result.success)
        assertTrue(result.observationText.contains("2023-11-14T22:13:20Z"), result.observationText)
    }

    @Test
    fun `all tools are read-only and register cleanly`() {
        val tools = SystemInfoTools.all(
            batteryPercent = { 100 },
            storageFreeBytes = { 0L },
            networkState = { "offline" },
        )
        val registry = ToolRegistry()
        tools.forEach { registry.register(it) }

        assertEquals(SystemInfoTools.manifestNames.sorted(), registry.all().map { it.name }.sorted())
        assertTrue(registry.all().all { it.tier == PermissionTier.READ_ONLY })
        assertEquals(tools.size, registry.definitions().size)
    }
}
