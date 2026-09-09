package com.jarvis.core.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ToolRegistryTest {
    private val battery = FakeTool("battery", PermissionTier.READ_ONLY)

    @Test
    fun `register, get and definitions round-trip a tool`() {
        val registry = ToolRegistry().apply { register(battery) }

        assertEquals(battery, registry.get("battery"))
        assertEquals(listOf("battery"), registry.definitions().map { it.name })
        assertEquals(battery.description, registry.definitions().single().description)
        assertEquals(battery.parametersSchemaJson, registry.definitions().single().parametersSchemaJson)
    }

    @Test
    fun `registering a duplicate name is rejected`() {
        val registry = ToolRegistry().apply { register(battery) }

        assertThrows(IllegalArgumentException::class.java) {
            registry.register(FakeTool("battery", PermissionTier.SENSITIVE))
        }
        assertEquals(battery, registry.get("battery"))
    }

    @Test
    fun `get on a missing tool returns null`() {
        assertNull(ToolRegistry().get("nope"))
    }

    @Test
    fun `unregister removes registered tool`() {
        val registry = ToolRegistry().apply { register(battery) }
        assertEquals(true, registry.unregister("battery"))
        assertNull(registry.get("battery"))
        assertEquals(false, registry.unregister("battery"))
    }

    @Test
    fun `registerOrReplace overwrites existing tool without throwing`() {
        val registry = ToolRegistry().apply { register(battery) }
        val updatedBattery = FakeTool("battery", PermissionTier.SENSITIVE)
        registry.registerOrReplace(updatedBattery)
        assertEquals(updatedBattery, registry.get("battery"))
        assertEquals(PermissionTier.SENSITIVE, registry.get("battery")?.tier)
    }

    @Test
    fun `mcp tool delegates execution to client`() = kotlinx.coroutines.test.runTest {
        val mcpClient = com.jarvis.core.agent.mcp.McpClient { name, args ->
            com.jarvis.core.agent.mcp.McpToolResponse(
                content = "Response from $name with args $args",
                isError = false,
            )
        }
        val mcpTool = com.jarvis.core.agent.mcp.McpTool(
            name = "custom_mcp_action",
            description = "External MCP tool",
            parametersSchemaJson = "{}",
            client = mcpClient,
        )

        val registry = ToolRegistry().apply { register(mcpTool) }
        assertEquals("custom_mcp_action", registry.get("custom_mcp_action")?.name)

        val result = registry.get("custom_mcp_action")!!.execute("{\"query\":\"test\"}")
        assertEquals(true, result.success)
        assertEquals("Response from custom_mcp_action with args {\"query\":\"test\"}", result.observationText)
    }
}
