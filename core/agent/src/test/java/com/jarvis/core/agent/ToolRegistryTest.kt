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
        assertEquals(battery, registry.get("battery")) // first registration wins
    }

    @Test
    fun `get on a missing tool returns null`() {
        assertNull(ToolRegistry().get("nope"))
    }
}
