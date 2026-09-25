package com.jarvis.core.agent.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionErrorMapperTest {

    @Test
    fun `user cancellation maps to clean cancelled state`() {
        val state = ExecutionErrorMapper.map(ErrorCode.USER_CANCELLED)
        assertEquals(UserFacingStateType.CANCELLED, state.type)
        assertEquals("Stopped.", state.message)
        assertFalse(state.requiresUserAction)
    }

    @Test
    fun `accessibility unavailable maps to actionable prompt`() {
        val state = ExecutionErrorMapper.map(
            ErrorCode.ACCESSIBILITY_UNAVAILABLE,
            details = mapOf("app" to "WhatsApp"),
        )
        assertEquals(UserFacingStateType.ACTION_REQUIRED, state.type)
        assertTrue(state.message.contains("Accessibility"))
        assertEquals("Open Accessibility Settings", state.suggestedAction)
        assertEquals("open_settings:accessibility", state.actionIntent)
        assertTrue(state.requiresUserAction)
    }

    @Test
    fun `permission required provides action intent`() {
        val state = ExecutionErrorMapper.map(
            ErrorCode.PERMISSION_REQUIRED,
            details = mapOf("app" to "Contacts"),
        )
        assertEquals(UserFacingStateType.ACTION_REQUIRED, state.type)
        assertTrue(state.message.contains("permission", ignoreCase = true))
        assertEquals("open_settings:permissions", state.actionIntent)
        assertTrue(state.requiresUserAction)
    }

    @Test
    fun `target not found includes target name and retry action`() {
        val state = ExecutionErrorMapper.map(
            ErrorCode.TARGET_NOT_FOUND,
            details = mapOf("target" to "Send button"),
        )
        assertEquals(UserFacingStateType.RECOVERABLE, state.type)
        assertTrue(state.message.contains("Send button"))
        assertEquals("Try again", state.suggestedAction)
        assertEquals("retry", state.actionIntent)
        assertFalse(state.requiresUserAction)
    }

    @Test
    fun `ambiguous target requires clarification`() {
        val state = ExecutionErrorMapper.map(
            ErrorCode.AMBIGUOUS_TARGET,
            details = mapOf("target" to "Rahul"),
        )
        assertEquals(UserFacingStateType.ACTION_REQUIRED, state.type)
        assertTrue(state.message.contains("multiple items", ignoreCase = true))
        assertEquals("clarify", state.actionIntent)
        assertTrue(state.requiresUserAction)
    }

    @Test
    fun `network error maps cleanly`() {
        val state = ExecutionErrorMapper.map(ErrorCode.NETWORK_UNAVAILABLE)
        assertEquals(UserFacingStateType.ACTION_REQUIRED, state.type)
        assertTrue(state.message.contains("internet", ignoreCase = true))
    }

    @Test
    fun `execution result success and failure builders produce correct statuses`() {
        val successRes = ExecutionResult.success("Done.")
        assertTrue(successRes.isSuccess)
        assertFalse(successRes.isCancelled)
        assertEquals(ExecutionStatus.SUCCESS, successRes.status)

        val failRes = ExecutionResult.failure(
            code = ErrorCode.TARGET_NOT_FOUND,
            message = "Control missing",
            details = mapOf("target" to "Search"),
        )
        assertFalse(failRes.isSuccess)
        assertEquals(ExecutionStatus.NOT_FOUND, failRes.status)
        assertTrue(failRes.retryable)
        assertNotNull(failRes.suggestedAction)
    }

    @Test
    fun `partial success tracks completed and pending steps`() {
        val partial = ExecutionResult.partialSuccess(
            message = "Opened WhatsApp but could not find contact",
            userMessage = "Opened WhatsApp, but couldn't find Rahul.",
            code = ErrorCode.TARGET_NOT_FOUND,
            completedSteps = listOf("launch_app"),
            pendingSteps = listOf("ui_click"),
        )
        assertTrue(partial.isSuccess)
        assertEquals(ExecutionStatus.PARTIAL_SUCCESS, partial.status)
        assertEquals(listOf("launch_app"), partial.completedSteps)
        assertEquals(listOf("ui_click"), partial.pendingSteps)
    }
}
