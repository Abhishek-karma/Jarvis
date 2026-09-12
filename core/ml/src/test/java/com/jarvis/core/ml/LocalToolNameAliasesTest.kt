package com.jarvis.core.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalToolNameAliasesTest {
    @Test
    fun `devicecontrol createfile resolves to create_file`() {
        assertEquals("create_file", LocalToolNameAliases.resolve("devicecontrol:createfile"))
        assertEquals("create_file", LocalToolNameAliases.resolve("devicecontrol:createFile"))
        assertEquals("create_file", LocalToolNameAliases.resolve("device_control:create_file"))
        assertEquals("create_file", LocalToolNameAliases.resolve("createfile"))
        assertEquals("create_file", LocalToolNameAliases.resolve("create_file"))
    }

    @Test
    fun `unknown names are rejected`() {
        assertNull(LocalToolNameAliases.resolve("unknown:foo"))
        assertNull(LocalToolNameAliases.resolve("devicecontrol:unknown"))
        assertNull(LocalToolNameAliases.resolve("devicecontrol:delete_everything"))
        assertNull(LocalToolNameAliases.resolve(""))
        assertNull(LocalToolNameAliases.resolve(null))
    }

    @Test
    fun `isKnown accepts registered names and aliases only`() {
        val registered = setOf("create_file", "read_file", "battery_level")
        assertTrue(LocalToolNameAliases.isKnown("create_file", registered))
        assertTrue(LocalToolNameAliases.isKnown("devicecontrol:createfile", registered))
        assertTrue(!LocalToolNameAliases.isKnown("devicecontrol:unknown", registered))
        assertTrue(!LocalToolNameAliases.isKnown("unknown:foo", registered))
    }
}
