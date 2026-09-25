package com.jarvis.core.agent.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeCoordinatorTest {

    @Test
    fun `policy engine evaluates blocked commands as BLOCKED`() {
        val policyEngine = CommandPolicyEngine()
        val eval = policyEngine.evaluate("rm -rf /")
        assertEquals(PolicyClassification.BLOCKED, eval.classification)
        assertFalse(policyEngine.isAllowedInBackground(TypedOp.ShellCommand("rm -rf /")))
    }

    @Test
    fun `policy engine evaluates dangerous commands as DOUBLE_CONFIRM`() {
        val policyEngine = CommandPolicyEngine()
        val eval = policyEngine.evaluate("pm clear com.example.app")
        assertEquals(PolicyClassification.DOUBLE_CONFIRM, eval.classification)
        assertFalse(policyEngine.isAllowedInBackground(TypedOp.ShellCommand("pm clear com.example.app")))
    }

    @Test
    fun `policy engine allows safe read commands in background`() {
        val policyEngine = CommandPolicyEngine()
        val eval = policyEngine.evaluate("dumpsys battery")
        assertEquals(PolicyClassification.ALLOWED, eval.classification)
        assertTrue(policyEngine.isAllowedInBackground(TypedOp.ShellCommand("dumpsys battery")))
    }

    @Test
    fun `typed ops read-only property matches background execution invariant`() {
        assertTrue(TypedOp.Screenshot.isReadOnly)
        assertTrue(TypedOp.ShellCommand("dumpsys battery").isReadOnly)
        assertTrue(TypedOp.ShellCommand("getprop ro.build.version.release").isReadOnly)
        assertFalse(TypedOp.GrantPermission("com.example", "android.permission.CAMERA").isReadOnly)
        assertFalse(TypedOp.RevokePermission("com.example", "android.permission.CAMERA").isReadOnly)
        assertFalse(TypedOp.ForceStop("com.example").isReadOnly)
    }

    // — shell injection regression tests —

    @Test
    fun `shellSafe rejects semicolons in package name`() {
        assertThrows(IllegalArgumentException::class.java) {
            shellSafe("com.x; rm -rf /")
        }
    }

    @Test
    fun `shellSafe rejects pipe metacharacter in permission`() {
        assertThrows(IllegalArgumentException::class.java) {
            shellSafe("android.permission.cam|id")
        }
    }

    @Test
    fun `shellSafe rejects ampersand in settings value`() {
        assertThrows(IllegalArgumentException::class.java) {
            shellSafe("x&calc")
        }
    }

    @Test
    fun `shellSafe rejects backtick injection in key`() {
        assertThrows(IllegalArgumentException::class.java) {
            shellSafe("key`whoami`")
        }
    }

    @Test
    fun `shellSafe rejects shell-dollar substitution in package name`() {
        assertThrows(IllegalArgumentException::class.java) {
            shellSafe("\${IFS}rm")
        }
    }

    @Test
    fun `shellSafe rejects single quotes injection`() {
        assertThrows(IllegalArgumentException::class.java) {
            shellSafe("com.example app'malicious")
        }
    }

    @Test
    fun `shellSafe accepts valid package names`() {
        shellSafe("com.example.app")
        shellSafe("android.permission.INTERNET")
        shellSafe("settings_global_timeout")
        shellSafe("allow")
        shellSafe("deny")
    }
}
