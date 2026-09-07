package com.jarvis.core.agent.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandPolicyEngineTest {

    private val engine = CommandPolicyEngine()

    @Test
    fun `blocked commands are strictly rejected`() {
        val dangerous = listOf(
            "rm -rf /",
            "rm -f /system/bin",
            "mkfs.ext4 /dev/block/bootdevice",
            "dd if=/dev/zero of=/dev/block/mmcblk0",
            "reboot bootloader",
            "wipe data",
            "pm uninstall com.android.settings",
            "settings put global device_provisioned 0",
        )

        for (cmd in dangerous) {
            val eval = engine.evaluate(cmd)
            assertEquals(PolicyClassification.BLOCKED, eval.classification, "Expected BLOCKED for: $cmd")
        }
    }

    @Test
    fun `double confirm commands require elevated confirmation`() {
        val risky = listOf(
            "pm clear com.example.app",
            "pm disable com.example.app",
            "rm -r /sdcard/Download/temp",
            "settings put global wifi_on 0",
            "kill -9 1234",
            "am force-stop com.example.app",
        )

        for (cmd in risky) {
            val eval = engine.evaluate(cmd)
            assertEquals(PolicyClassification.DOUBLE_CONFIRM, eval.classification, "Expected DOUBLE_CONFIRM for: $cmd")
        }
    }

    @Test
    fun `safe read-only inspection commands are classified as ALLOWED`() {
        val safe = listOf(
            "dumpsys battery",
            "getprop ro.build.version.release",
            "pm list packages",
            "settings get global wifi_on",
            "uptime",
            "df -h",
            "ls -la /sdcard",
        )

        for (cmd in safe) {
            val eval = engine.evaluate(cmd)
            assertEquals(PolicyClassification.ALLOWED, eval.classification, "Expected ALLOWED for: $cmd")
        }
    }

    @Test
    fun `background routine run rejects non-read-only operations`() {
        assertFalse(engine.isAllowedInBackground(TypedOp.ShellCommand("rm -rf /sdcard/test")))
        assertFalse(engine.isAllowedInBackground(TypedOp.GrantPermission("com.app", "android.permission.CAMERA")))
        assertFalse(engine.isAllowedInBackground(TypedOp.ForceStop("com.app")))

        assertTrue(engine.isAllowedInBackground(TypedOp.ShellCommand("dumpsys battery")))
        assertTrue(engine.isAllowedInBackground(TypedOp.Screenshot))
    }
}
