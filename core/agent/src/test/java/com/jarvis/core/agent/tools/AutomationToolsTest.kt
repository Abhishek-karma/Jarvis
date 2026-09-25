package com.jarvis.core.agent.tools

import com.jarvis.core.agent.automation.UiElementSnapshot
import com.jarvis.core.agent.automation.UiWindowSnapshot
import com.jarvis.core.agent.needle.NeedleRouter
import com.jarvis.core.agent.needle.RoutingDecision
import com.jarvis.core.common.PermissionTier
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutomationToolsTest {

    @Test
    fun `ui window snapshot formats into clean readable compact hierarchy`() {
        val snapshot = UiWindowSnapshot(
            packageName = "com.whatsapp",
            windowTitle = "Rahul chat",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Type a message",
                    contentDescription = null,
                    viewId = "entry",
                    className = "EditText",
                    isClickable = true,
                    isEditable = true,
                    isScrollable = false,
                ),
                UiElementSnapshot(
                    index = 2,
                    text = null,
                    contentDescription = "Send",
                    viewId = "send_btn",
                    className = "ImageButton",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                ),
                UiElementSnapshot(
                    index = 3,
                    text = "Rahul",
                    contentDescription = null,
                    viewId = "conversation_contact_name",
                    className = "TextView",
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                ),
            ),
        )

        val formatted = snapshot.format()
        assertTrue(formatted.contains("PACKAGE: com.whatsapp"))
        assertTrue(formatted.contains("SCREEN: Rahul chat"))
        assertTrue(formatted.contains("1. Type a message | type=text_input, clickable=true, editable=true [id: entry]"))
        assertTrue(formatted.contains("2. Send | type=button, clickable=true [id: send_btn]"))
        assertTrue(formatted.contains("3. Rahul"))
    }

    @Test
    fun `needle router preserves multi-step phone automation tasks for agent runner`() = runTest {
        val router = NeedleRouter()

        // Multi-step complex tasks must NOT be intercepted as single-shot direct capabilities;
        // they must flow to AgentRunner so the LLM generates the sequence dynamically.
        val r1 = router.route("Open WhatsApp, find Rahul, type 'I'm on my way', and send it.")
        assertTrue(r1 is RoutingDecision.Escalate)

        val r2 = router.route("Open WhatsApp and message Rahul about the meeting")
        assertTrue(r2 is RoutingDecision.Escalate)

        val r3 = router.route("Open Instagram and search for OpenAI")
        assertTrue(r3 is RoutingDecision.Escalate)

        val r4 = router.route("Open YouTube, search for Android development, and open the first result.")
        assertTrue(r4 is RoutingDecision.Escalate)

        val r5 = router.route("open play store search youtube then download")
        assertTrue(r5 is RoutingDecision.Escalate)
    }

    @Test
    fun `needle router routes settings wifi to open_settings intent`() = runTest {
        val router = NeedleRouter()
        val resolvedDirect = router.route("Open Wi-Fi settings")
        assertTrue(resolvedDirect is RoutingDecision.Direct)
        val actionDirect = resolvedDirect as RoutingDecision.Direct
        assertEquals(DeviceTools.OPEN_SETTINGS, actionDirect.toolName)
        assertTrue(actionDirect.argsJson.contains("\"setting_type\":\"wifi\""))

        val resolvedWifiOnly = router.route("Wi-Fi settings")
        assertTrue(resolvedWifiOnly is RoutingDecision.Direct)
        assertEquals(DeviceTools.OPEN_SETTINGS, (resolvedWifiOnly as RoutingDecision.Direct).toolName)

        val resolvedWifiNoHyphen = router.route("wifi settings")
        assertTrue(resolvedWifiNoHyphen is RoutingDecision.Direct)
        assertEquals(DeviceTools.OPEN_SETTINGS, (resolvedWifiNoHyphen as RoutingDecision.Direct).toolName)
    }

    @Test
    fun `needle router routes open youtube to launch_app intent`() = runTest {
        val router = NeedleRouter()
        val resolved = router.route("Open YouTube")
        assertTrue(resolved is RoutingDecision.Direct)
        val action = resolved as RoutingDecision.Direct
        assertEquals(DeviceTools.LAUNCH_APP, action.toolName)
        assertTrue(action.argsJson.contains("\"app_name\":\"youtube\""))

        // Also with trailing punctuation
        val resolvedWithDot = router.route("Open YouTube.")
        assertTrue(resolvedWithDot is RoutingDecision.Direct)
        assertEquals(DeviceTools.LAUNCH_APP, (resolvedWithDot as RoutingDecision.Direct).toolName)
    }

    @Test
    fun `needle router routes primary regression direct commands correctly`() = runTest {
        val router = NeedleRouter()

        // 1. "Open WhatsApp" -> launch_app
        val wa = router.route("Open WhatsApp")
        assertTrue(wa is RoutingDecision.Direct)
        assertEquals(DeviceTools.LAUNCH_APP, (wa as RoutingDecision.Direct).toolName)
        assertTrue(wa.argsJson.contains("\"app_name\":\"whatsapp\""))

        // 2. "Open Settings" -> open_settings
        val settings = router.route("Open Settings")
        assertTrue(settings is RoutingDecision.Direct)
        assertEquals(DeviceTools.OPEN_SETTINGS, (settings as RoutingDecision.Direct).toolName)
        assertTrue(settings.argsJson.contains("\"setting_type\":\"general\""))

        // 3. "Open Bluetooth settings" -> open_settings(bluetooth)
        val bt = router.route("Open Bluetooth settings")
        assertTrue(bt is RoutingDecision.Direct)
        assertEquals(DeviceTools.OPEN_SETTINGS, (bt as RoutingDecision.Direct).toolName)
        assertTrue(bt.argsJson.contains("\"setting_type\":\"bluetooth\""))

        // 4. "turn on flashlight" -> set_flashlight(enabled=true)
        val flashOn = router.route("turn on flashlight")
        assertTrue(flashOn is RoutingDecision.Direct)
        assertEquals(DeviceTools.SET_FLASHLIGHT, (flashOn as RoutingDecision.Direct).toolName)
        assertTrue(flashOn.argsJson.contains("\"enabled\":true"))

        // 5. "turn off flashlight" -> set_flashlight(enabled=false)
        val flashOff = router.route("turn off flashlight")
        assertTrue(flashOff is RoutingDecision.Direct)
        assertEquals(DeviceTools.SET_FLASHLIGHT, (flashOff as RoutingDecision.Direct).toolName)
        assertTrue(flashOff.argsJson.contains("\"enabled\":false"))

        // 6. Battery level
        val battery = router.route("what is my battery level?")
        assertTrue(battery is RoutingDecision.Direct)
        assertEquals(SystemInfoTools.BATTERY_LEVEL, (battery as RoutingDecision.Direct).toolName)
    }

    @Test
    fun `automation tools manifest exposes only phone_agent to agent runner`() {
        val tools = AutomationTools.all(launchApp = { Result.success(Unit) }, serviceProvider = { null })
        assertEquals(1, tools.size)
        assertEquals(AutomationTools.PHONE_AGENT, tools[0].name)
    }

    @Test
    fun `internal automation primitives register with correct security tiers`() {
        val controller = com.jarvis.core.agent.automation.AutomationController { null }
        val tools = AutomationTools.internalPrimitives(controller)

        val observe = tools.first { it.name == AutomationTools.UI_OBSERVE }
        assertEquals(PermissionTier.READ_ONLY, observe.tier)

        val click = tools.first { it.name == AutomationTools.UI_CLICK }
        assertEquals(PermissionTier.REVERSIBLE_WRITE, click.tier)

        val type = tools.first { it.name == AutomationTools.UI_TYPE }
        assertEquals(PermissionTier.REVERSIBLE_WRITE, type.tier)

        val scroll = tools.first { it.name == AutomationTools.UI_SCROLL }
        assertEquals(PermissionTier.REVERSIBLE_WRITE, scroll.tier)

        val swipe = tools.first { it.name == AutomationTools.UI_SWIPE }
        assertEquals(PermissionTier.REVERSIBLE_WRITE, swipe.tier)

        val back = tools.first { it.name == AutomationTools.UI_BACK }
        assertEquals(PermissionTier.REVERSIBLE_WRITE, back.tier)

        val verify = tools.first { it.name == AutomationTools.UI_VERIFY }
        assertEquals(PermissionTier.READ_ONLY, verify.tier)
    }

    @Test
    fun `automation primitives fail gracefully when accessibility service is not active`() = runTest {
        val controller = com.jarvis.core.agent.automation.AutomationController { null }
        val tools = AutomationTools.internalPrimitives(controller)
        val observe = tools.first { it.name == AutomationTools.UI_OBSERVE }
        val result = observe.execute("{}")

        assertFalse(result.success)
        assertEquals(com.jarvis.core.agent.automation.AutomationErrorCodes.SERVICE_UNAVAILABLE, result.error)
        assertTrue(result.observationText.contains("Screen observation failed"))
    }

    @Test
    fun `needle router routes download app to install_app`() = runTest {
        val router = NeedleRouter()
        val resolved = router.route("download youtube from play store")
        assertTrue(resolved is RoutingDecision.Direct)
        val action = resolved as RoutingDecision.Direct
        assertEquals(DeviceTools.INSTALL_APP, action.toolName)
        assertTrue(action.argsJson.contains("\"app_name\":\"youtube\""))

        val resolvedDirect = router.route("install whatsapp")
        assertTrue(resolvedDirect is RoutingDecision.Direct)
        val actionDirect = resolvedDirect as RoutingDecision.Direct
        assertEquals(DeviceTools.INSTALL_APP, actionDirect.toolName)
        assertTrue(actionDirect.argsJson.contains("\"app_name\":\"whatsapp\""))
    }

    @Test
    fun `automation internal primitives include validation for missing arguments`() = runTest {
        val controller = com.jarvis.core.agent.automation.AutomationController { null }
        val tools = AutomationTools.internalPrimitives(controller)
        val clickTool = tools.first { it.name == AutomationTools.UI_CLICK }
        val swipeTool = tools.first { it.name == AutomationTools.UI_SWIPE }
        val scrollTool = tools.first { it.name == AutomationTools.UI_SCROLL }
        val typeTool = tools.first { it.name == AutomationTools.UI_TYPE }

        // ui_click requires target or coordinates
        val clickMissing = clickTool.execute("{}")
        assertFalse(clickMissing.success)
        assertTrue(clickMissing.observationText.contains("Missing 'target' or ('x', 'y')"))

        // ui_click with coordinates
        val clickCoords = clickTool.execute("""{"x":540.0,"y":960.0}""")
        assertFalse(clickCoords.success) // service unavailable, but parsed coordinates correctly
        assertEquals(com.jarvis.core.agent.automation.AutomationErrorCodes.SERVICE_UNAVAILABLE, clickCoords.error)

        // ui_swipe missing coordinates
        val swipeMissing = swipeTool.execute("""{"start_x":100}""")
        assertFalse(swipeMissing.success)
        assertEquals("missing_coordinates", swipeMissing.error)

        // ui_type missing text
        val typeMissing = typeTool.execute("{}")
        assertFalse(typeMissing.success)
        assertEquals("missing_text", typeMissing.error)

        // ui_submit
        val submitTool = tools.first { it.name == AutomationTools.UI_SUBMIT }
        val submitResult = submitTool.execute("{}")
        assertFalse(submitResult.success)
        assertEquals(com.jarvis.core.agent.automation.AutomationErrorCodes.SERVICE_UNAVAILABLE, submitResult.error)

        // ui_scroll with left/right directions
        val scrollLeft = scrollTool.execute("""{"direction":"left"}""")
        assertFalse(scrollLeft.success)
        assertEquals(com.jarvis.core.agent.automation.AutomationErrorCodes.SERVICE_UNAVAILABLE, scrollLeft.error)
    }
}
