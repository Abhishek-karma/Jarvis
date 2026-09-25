package com.jarvis.core.agent.automation.engine

import android.graphics.Bitmap
import com.jarvis.core.agent.automation.AutomationActionResult
import com.jarvis.core.agent.automation.UiWindowSnapshot

/**
 * Driver abstraction for low-level device and accessibility interaction.
 * Provides clean primitive operations without domain or app-specific logic.
 * Follows the ClosePaw/MobileAgent device driver architecture.
 */
interface PhoneAutomationDriver {
    suspend fun observe(): Result<UiWindowSnapshot>
    suspend fun tap(target: String = "", x: Float? = null, y: Float? = null): AutomationActionResult
    suspend fun type(text: String, target: String? = null, submit: Boolean = false, clearFirst: Boolean = false): AutomationActionResult
    suspend fun submit(target: String? = null): AutomationActionResult
    suspend fun scroll(direction: String, targetContainer: String? = null): AutomationActionResult
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 320L): AutomationActionResult
    suspend fun back(): AutomationActionResult
    suspend fun verify(expected: String, timeoutMs: Long = 2000L): AutomationActionResult
    suspend fun captureScreenshot(): Bitmap? = null
}
