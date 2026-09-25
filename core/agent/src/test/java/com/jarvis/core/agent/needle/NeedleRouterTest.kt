package com.jarvis.core.agent.needle

import com.jarvis.core.agent.tools.DeviceTools
import com.jarvis.core.agent.tools.MediaTools
import com.jarvis.core.agent.tools.SystemInfoTools
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NeedleRouterTest {

    private val router = NeedleRouter()

    @Test
    fun `routes open whatsapp to launch_app with high confidence`() = runTest {
        val decision = router.route("Open WhatsApp")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.LAUNCH_APP, direct.toolName)
        assertTrue(direct.argsJson.contains("\"app_name\":\"whatsapp\""))
        assertTrue(direct.confidence >= 0.80f)
    }

    @Test
    fun `routes launch youtube with trailing dot to launch_app`() = runTest {
        val decision = router.route("Launch YouTube.")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.LAUNCH_APP, direct.toolName)
        assertTrue(direct.argsJson.contains("\"app_name\":\"youtube\""))
    }

    @Test
    fun `routes open wifi settings to open_settings`() = runTest {
        val decision = router.route("Open Wi-Fi settings")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.OPEN_SETTINGS, direct.toolName)
        assertTrue(direct.argsJson.contains("\"setting_type\":\"wifi\""))
    }

    @Test
    fun `routes open settings to general settings`() = runTest {
        val decision = router.route("Open Settings")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.OPEN_SETTINGS, direct.toolName)
        assertTrue(direct.argsJson.contains("\"setting_type\":\"general\""))
    }

    @Test
    fun `routes bluetooth settings to open_settings bluetooth`() = runTest {
        val decision = router.route("Open Bluetooth settings")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.OPEN_SETTINGS, direct.toolName)
        assertTrue(direct.argsJson.contains("\"setting_type\":\"bluetooth\""))
    }

    @Test
    fun `routes turn on flashlight to set_flashlight enabled true`() = runTest {
        val decision = router.route("turn on flashlight")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.SET_FLASHLIGHT, direct.toolName)
        assertTrue(direct.argsJson.contains("\"enabled\":true"))
    }

    @Test
    fun `routes turn off flashlight to set_flashlight enabled false`() = runTest {
        val decision = router.route("turn off flashlight")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.SET_FLASHLIGHT, direct.toolName)
        assertTrue(direct.argsJson.contains("\"enabled\":false"))
    }

    @Test
    fun `routes battery question to battery_level`() = runTest {
        val decision = router.route("How much battery do I have?")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(SystemInfoTools.BATTERY_LEVEL, direct.toolName)
    }

    @Test
    fun `routes time query to get_current_datetime`() = runTest {
        val decision = router.route("What time is it?")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(SystemInfoTools.GET_CURRENT_DATETIME, direct.toolName)
    }

    @Test
    fun `routes volume adjustment to adjust_volume`() = runTest {
        val decision = router.route("Mute volume")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(MediaTools.ADJUST_VOLUME, direct.toolName)
        assertTrue(direct.argsJson.contains("\"action\":\"mute\""))
    }

    @Test
    fun `routes play jazz music to play_media`() = runTest {
        val decision = router.route("Play jazz music")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(MediaTools.PLAY_MEDIA, direct.toolName)
        assertTrue(direct.argsJson.contains("\"query\":\"jazz music\""))
    }

    @Test
    fun `routes play song on spotify to play_media with target app`() = runTest {
        val decision = router.route("Play Blinding Lights on Spotify")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(MediaTools.PLAY_MEDIA, direct.toolName)
        assertTrue(direct.argsJson.contains("\"query\":\"Blinding Lights\""))
        assertTrue(direct.argsJson.contains("\"app_name\":\"spotify\""))
    }

    @Test
    fun `routes pause and resume to media_control`() = runTest {
        val pauseDecision = router.route("pause music")
        assertTrue(pauseDecision is RoutingDecision.Direct)
        assertEquals(MediaTools.MEDIA_CONTROL, (pauseDecision as RoutingDecision.Direct).toolName)
        assertTrue(pauseDecision.argsJson.contains("\"action\":\"pause\""))

        val resumeDecision = router.route("resume music")
        assertTrue(resumeDecision is RoutingDecision.Direct)
        assertEquals(MediaTools.MEDIA_CONTROL, (resumeDecision as RoutingDecision.Direct).toolName)
        assertTrue(resumeDecision.argsJson.contains("\"action\":\"play\""))
    }

    @Test
    fun `routes download app to install_app`() = runTest {
        val decision = router.route("download Spotify from play store")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.INSTALL_APP, direct.toolName)
        assertTrue(direct.argsJson.contains("\"app_name\":\"spotify\""))
    }

    @Test
    fun `routes list apps query to list_installed_apps`() = runTest {
        val decision = router.route("What apps are installed?")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.LIST_INSTALLED_APPS, direct.toolName)
    }

    @Test
    fun `routes open instagram to launch_app with high confidence`() = runTest {
        val decision = router.route("Open Instagram")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.LAUNCH_APP, direct.toolName)
        assertTrue(direct.argsJson.contains("\"app_name\":\"instagram\""))
        assertTrue(direct.confidence >= 0.80f)
    }

    @Test
    fun `routes open youtube to launch_app`() = runTest {
        val decision = router.route("Open YouTube")
        assertTrue(decision is RoutingDecision.Direct)
        val direct = decision as RoutingDecision.Direct
        assertEquals(DeviceTools.LAUNCH_APP, direct.toolName)
        assertTrue(direct.argsJson.contains("\"app_name\":\"youtube\""))
    }

    @Test
    fun `escalates complex multi-step phone automation tasks to AgentRunner`() = runTest {
        // Multi-step complex tasks must NOT be intercepted as single-shot direct capabilities;
        // they must escalate so the LLM AgentRunner generates the sequence dynamically.
        val r1 = router.route("Open WhatsApp, find Rahul, type 'I'm on my way', and send it.")
        assertTrue(r1 is RoutingDecision.Escalate)

        val r2 = router.route("Open WhatsApp and message Rahul saying I'm on my way")
        assertTrue(r2 is RoutingDecision.Escalate)

        val r3 = router.route("Open Instagram and search for OpenAI")
        assertTrue(r3 is RoutingDecision.Escalate)

        val r4 = router.route("Open YouTube, search for Android development, and open the first result.")
        assertTrue(r4 is RoutingDecision.Escalate)

        val r5 = router.route("open play store search youtube then download")
        assertTrue(r5 is RoutingDecision.Escalate)

        // Exact regression test cases from issue report
        val r6 = router.route("Open Instagram and search OpenAI")
        assertTrue(r6 is RoutingDecision.Escalate)

        val r7 = router.route("Open YouTube and search Android")
        assertTrue(r7 is RoutingDecision.Escalate)

        val r8 = router.route("Open WhatsApp search Ravi")
        assertTrue(r8 is RoutingDecision.Escalate)

        val r9 = router.route("Open WhatsApp and find Ravi")
        assertTrue(r9 is RoutingDecision.Escalate)

        val r10 = router.route("Open WhatsApp, find Ravi, type I'm on my way and send it")
        assertTrue(r10 is RoutingDecision.Escalate)
    }

    @Test
    fun `escalates empty or blank prompts`() = runTest {
        val r = router.route("   ")
        assertTrue(r is RoutingDecision.Escalate)
        assertEquals(EscalationReason.EMPTY_PROMPT, (r as RoutingDecision.Escalate).reason)
    }

    @Test
    fun `needleAction delegates directly to ToolExecutor`() = runTest {
        val registry = com.jarvis.core.agent.ToolRegistry()
        registry.register(com.jarvis.core.agent.tools.DeviceTools.setFlashlight { enabled ->
            Result.success(Unit)
        })
        val toolExecutor = com.jarvis.core.agent.ToolExecutor(
            registry = registry,
            audit = com.jarvis.core.agent.AuditLogger { },
        )
        val needleActionTool = NeedleTools.needleAction(router, toolExecutor)

        val result = needleActionTool.execute("""{"action":"turn on flashlight"}""")
        assertTrue(result.success)
        assertTrue(result.observationText.isNotBlank())
    }

    @Test
    fun `needleAction blocks recursive needle_action execution`() = runTest {
        val registry = com.jarvis.core.agent.ToolRegistry()
        val toolExecutor = com.jarvis.core.agent.ToolExecutor(
            registry = registry,
            audit = com.jarvis.core.agent.AuditLogger { },
        )
        val needleActionTool = NeedleTools.needleAction(
            needleRouter = object : NeedleRouter() {
                override suspend fun route(prompt: String): RoutingDecision {
                    return RoutingDecision.Direct(
                        toolName = NeedleTools.NEEDLE_ACTION,
                        argsJson = """{"action":"turn on flashlight"}""",
                        confidence = 1.0f,
                        userFacingAction = "recursive test",
                    )
                }
            },
            toolExecutor = toolExecutor,
        )

        val result = needleActionTool.execute("""{"action":"turn on flashlight"}""")
        assertEquals(false, result.success)
        assertEquals("recursive_needle_call", result.error)
    }
}
