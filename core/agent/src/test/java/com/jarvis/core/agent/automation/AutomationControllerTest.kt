package com.jarvis.core.agent.automation

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutomationControllerTest {

    @Test
    fun `controller starts in idle state`() {
        val controller = AutomationController(serviceProvider = { null })
        assertEquals(AutomationState.IDLE, controller.currentState)
    }

    @Test
    fun `controller fails gracefully with structured code when service is unavailable`() = runTest {
        val controller = AutomationController(serviceProvider = { null })

        val clickRes = controller.tap("Search")
        assertFalse(clickRes.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, clickRes.errorCode)
        assertTrue(clickRes.errorMessage?.contains("Accessibility service is unavailable") == true)

        val typeRes = controller.type("Hello", "input_1")
        assertFalse(typeRes.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, typeRes.errorCode)

        val scrollRes = controller.scroll("down")
        assertFalse(scrollRes.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, scrollRes.errorCode)

        val backRes = controller.goBack()
        assertFalse(backRes.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, backRes.errorCode)

        val verifyRes = controller.verify(expected = "Home")
        assertFalse(verifyRes.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, verifyRes.errorCode)
    }

    @Test
    fun `controller formats structured observation text correctly for success and failure`() {
        val successResult = AutomationActionResult(
            success = true,
            action = "click",
            target = "Send",
            method = "accessibility_click",
            packageName = "com.whatsapp",
            stateChanged = true,
            updatedSnapshot = UiWindowSnapshot(
                packageName = "com.whatsapp",
                windowTitle = "Chat",
                elements = listOf(
                    UiElementSnapshot(
                        index = 1,
                        text = "Message sent",
                        contentDescription = null,
                        viewId = "status",
                        className = "TextView",
                        isClickable = false,
                        isEditable = false,
                    ),
                ),
            ),
        )

        val successText = successResult.toObservationText()
        assertTrue(successText.contains("Action \"click\" succeeded on \"Send\" via accessibility_click."))
        assertTrue(successText.contains("Active package: com.whatsapp"))
        assertTrue(successText.contains("1. Message sent"))

        val ambiguousResult = AutomationActionResult(
            success = false,
            action = "click",
            target = "More",
            errorCode = AutomationErrorCodes.AMBIGUOUS_TARGET,
            candidateMatches = listOf(
                UiElementSnapshot(
                    index = 2,
                    text = "More options",
                    contentDescription = null,
                    viewId = "menu_more",
                    className = "ImageButton",
                    isClickable = true,
                    isEditable = false,
                ),
                UiElementSnapshot(
                    index = 5,
                    text = "Read more",
                    contentDescription = null,
                    viewId = "text_more",
                    className = "TextView",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val ambiguousText = ambiguousResult.toObservationText()
        assertTrue(ambiguousText.contains("Ambiguous target. Multiple candidates match:"))
        assertTrue(ambiguousText.contains("[2] More options"))
        assertTrue(ambiguousText.contains("[5] Read more"))
        assertTrue(ambiguousText.contains("target=\"2\""))
    }

    @Test
    fun `ui window snapshot correctly marks system permission dialogs`() {
        val permissionSnapshot = UiWindowSnapshot(
            packageName = "com.android.permissioncontroller",
            windowTitle = "Allow permissions",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Allow",
                    contentDescription = null,
                    viewId = "permission_allow_button",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
            isPermissionDialog = true,
        )

        val formatted = permissionSnapshot.format()
        assertTrue(formatted.contains("NOTICE: Android system permission request dialog detected."))
        assertTrue(formatted.contains("1. Allow | type=button, clickable=true [id: permission_allow_button]"))
    }

    @Test
    fun `ui element snapshots categorize types appropriately`() {
        val editEl = UiElementSnapshot(
            index = 1,
            text = "Type here",
            contentDescription = null,
            viewId = "entry",
            className = "EditText",
            isClickable = true,
            isEditable = true,
        )
        assertEquals("text_input", editEl.type)

        val switchEl = UiElementSnapshot(
            index = 2,
            text = "Wi-Fi",
            contentDescription = null,
            viewId = "switch_widget",
            className = "android.widget.Switch",
            isClickable = true,
            isEditable = false,
        )
        assertEquals("switch", switchEl.type)

        val checkboxEl = UiElementSnapshot(
            index = 3,
            text = "Remember me",
            contentDescription = null,
            viewId = "checkbox",
            className = "CheckBox",
            isClickable = true,
            isEditable = false,
            isChecked = true,
        )
        assertEquals("checkbox", checkboxEl.type)
    }

    @Test
    fun `controller handles coordinate tap parsing and service checks`() = runTest {
        val controller = AutomationController(serviceProvider = { null })
        val res = controller.tap(target = "", x = 500f, y = 1000f)
        assertFalse(res.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, res.errorCode)

        val resCoordStr = controller.tap(target = "(540, 960)")
        assertFalse(resCoordStr.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, resCoordStr.errorCode)
    }

    @Test
    fun `controller handles swipe gesture execution and service checks`() = runTest {
        val controller = AutomationController(serviceProvider = { null })
        val res = controller.swipe(startX = 500f, startY = 1500f, endX = 500f, endY = 500f)
        assertFalse(res.success)
        assertEquals(AutomationErrorCodes.SERVICE_UNAVAILABLE, res.errorCode)
    }
}
