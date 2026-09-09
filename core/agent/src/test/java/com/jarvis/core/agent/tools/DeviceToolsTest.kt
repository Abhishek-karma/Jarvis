package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceToolsTest {

    @Test
    fun `device tools declare correct permission tiers`() {
        assertEquals(PermissionTier.REVERSIBLE_WRITE, DeviceTools.launchApp { Result.success(Unit) }.tier)
        assertEquals(PermissionTier.REVERSIBLE_WRITE, DeviceTools.copyToClipboard { _, _ -> Result.success(Unit) }.tier)
        assertEquals(PermissionTier.READ_ONLY, DeviceTools.readClipboard { Result.success(null) }.tier)
        assertEquals(PermissionTier.REVERSIBLE_WRITE, DeviceTools.showNotification { _, _ -> Result.success(Unit) }.tier)
    }

    @Test
    fun `launch_app accepts package or app name`() = runBlocking {
        var launched = ""
        val tool = DeviceTools.launchApp { target ->
            launched = target
            Result.success(Unit)
        }

        val res = tool.execute("""{"package_name":"com.google.android.youtube"}""")
        assertTrue(res.success)
        assertEquals("com.google.android.youtube", launched)

        val missing = tool.execute("""{}""")
        assertFalse(missing.success)
    }

    @Test
    fun `copy and read clipboard tools work as expected`() = runBlocking {
        var clipText = ""
        var clipLabel: String? = null

        val copyTool = DeviceTools.copyToClipboard { text, label ->
            clipText = text
            clipLabel = label
            Result.success(Unit)
        }
        val readTool = DeviceTools.readClipboard { Result.success(clipText) }

        val copyRes = copyTool.execute("""{"text":"secret token","label":"auth"}""")
        assertTrue(copyRes.success)
        assertEquals("secret token", clipText)
        assertEquals("auth", clipLabel)

        val readRes = readTool.execute("""{}""")
        assertTrue(readRes.success)
        assertTrue(readRes.observationText.contains("secret token"))
    }

    @Test
    fun `show_notification verifies required message`() = runBlocking {
        var notifTitle = ""
        var notifMsg = ""
        val tool = DeviceTools.showNotification { title, message ->
            notifTitle = title
            notifMsg = message
            Result.success(Unit)
        }

        val bad = tool.execute("""{"title":"Hi"}""")
        assertFalse(bad.success)

        val good = tool.execute("""{"title":"Reminder","message":"Drink water"}""")
        assertTrue(good.success)
        assertEquals("Reminder", notifTitle)
        assertEquals("Drink water", notifMsg)
    }
}
