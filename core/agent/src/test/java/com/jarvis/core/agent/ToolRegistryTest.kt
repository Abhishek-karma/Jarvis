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
    fun `custom tool delegates execution properly`() = kotlinx.coroutines.test.runTest {
        val customTool = object : Tool {
            override val name: String = "custom_action"
            override val description: String = "Custom tool"
            override val parametersSchemaJson: String = "{}"
            override val tier: PermissionTier = PermissionTier.READ_ONLY
            override suspend fun execute(argsJson: String): ToolResult =
                ToolResult(success = true, observationText = "Executed custom_action with args $argsJson")
        }

        val registry = ToolRegistry().apply { register(customTool) }
        assertEquals("custom_action", registry.get("custom_action")?.name)

        val result = registry.get("custom_action")!!.execute("{\"query\":\"test\"}")
        assertEquals(true, result.success)
        assertEquals("Executed custom_action with args {\"query\":\"test\"}", result.observationText)
    }
}
