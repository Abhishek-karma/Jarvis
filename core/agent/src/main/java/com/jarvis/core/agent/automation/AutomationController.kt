package com.jarvis.core.agent.automation

import android.graphics.Bitmap
import com.jarvis.core.agent.automation.engine.PhoneAutomationDriver
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller owning automation state, retries, timeouts, verification, and loop execution.
 *
 * Coordinates between Needle 3 decision layer, AgentRunner, and low-level AccessibilityService.
 */
@Singleton
class AutomationController @Inject constructor(
    private val serviceProvider: () -> JarvisAccessibilityService? = { JarvisAccessibilityService.instance },
) : PhoneAutomationDriver {
    private val _currentState = AtomicReference(AutomationState.IDLE)
    val currentState: AutomationState get() = _currentState.get()

    /**
     * Observes the active screen and retrieves a clean semantic representation.
     */
    override suspend fun observe(): Result<UiWindowSnapshot> {
        _currentState.set(AutomationState.OBSERVING)
        val service = serviceProvider()
            ?: return Result.failure(IllegalStateException("Accessibility service is unavailable."))

        val snapshot = service.inspectActiveWindow()
            ?: return Result.failure(IllegalStateException("No active window found or screen content could not be read."))

        if (snapshot.isPermissionDialog) {
            _currentState.set(AutomationState.BLOCKED)
        } else {
            _currentState.set(AutomationState.IDLE)
        }

        return Result.success(snapshot)
    }

    /**
     * Executes a tap/click primitive on the target element with disambiguation and gesture fallback.
     */
    override suspend fun tap(
        target: String,
        x: Float?,
        y: Float?,
    ): AutomationActionResult {
        _currentState.set(AutomationState.ACTING)
        val service = serviceProvider()
            ?: return AutomationActionResult(
                success = false,
                action = "click",
                target = target,
                errorCode = AutomationErrorCodes.SERVICE_UNAVAILABLE,
                errorMessage = "Accessibility service is unavailable. Please enable Jarvis Accessibility Service in Android Settings.",
            )

        // 1. Direct coordinate tap if explicit coordinates are provided
        if (x != null && y != null && x > 0f && y > 0f) {
            val prevRev = service.uiRevision
            _currentState.set(AutomationState.WAITING)
            val tapped = service.tapCoordinates(x, y)
            if (tapped) {
                service.waitForUiChange(prevRev, timeoutMs = 1200L)
                val freshSnapshot = service.inspectActiveWindow()
                _currentState.set(AutomationState.SUCCEEDED)
                return AutomationActionResult(
                    success = true,
                    action = "click",
                    target = "($x, $y)",
                    method = "gesture_coordinate_tap",
                    packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                    stateChanged = true,
                    updatedSnapshot = freshSnapshot,
                )
            }
        }

        // 2. Parse coordinate string e.g. "540, 960" or "(540, 960)"
        val coordMatch = Regex("""\(?\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\)?""").matchEntire(target.trim())
        if (coordMatch != null) {
            val parsedX = coordMatch.groupValues[1].toFloatOrNull()
            val parsedY = coordMatch.groupValues[2].toFloatOrNull()
            if (parsedX != null && parsedY != null && parsedX > 0f && parsedY > 0f) {
                return tap(target = "", x = parsedX, y = parsedY)
            }
        }

        if (target.isBlank()) {
            return AutomationActionResult(
                success = false,
                action = "click",
                errorCode = AutomationErrorCodes.TARGET_NOT_FOUND,
                errorMessage = "Missing target identifier for click.",
            )
        }

        var resolution = service.resolveTarget(target)
        if (resolution is TargetResolution.NotFound) {
            // UI stabilization retry: wait 300ms once in case window is transitioning
            delay(300L)
            resolution = service.resolveTarget(target)
        }

        when (resolution) {
            is TargetResolution.Ambiguous -> {
                _currentState.set(AutomationState.AMBIGUOUS)
                val snapshot = service.inspectActiveWindow()
                return AutomationActionResult(
                    success = false,
                    action = "click",
                    target = target,
                    errorCode = AutomationErrorCodes.AMBIGUOUS_TARGET,
                    errorMessage = "Multiple elements match \"$target\".",
                    candidateMatches = resolution.matches,
                    updatedSnapshot = snapshot,
                )
            }
            is TargetResolution.NotFound -> {
                // Secondary fallback: search active window elements for partial/label match and gesture tap
                val snapshot = service.inspectActiveWindow()
                val matchedEl = snapshot?.elements?.firstOrNull {
                    it.displayLabel.contains(target, ignoreCase = true) ||
                        it.viewId?.contains(target, ignoreCase = true) == true ||
                        it.contentDescription?.contains(target, ignoreCase = true) == true
                }
                if (matchedEl?.bounds != null && !matchedEl.bounds.isEmpty) {
                    val prevRev = service.uiRevision
                    val tapped = service.tapCoordinates(
                        matchedEl.bounds.centerX().toFloat(),
                        matchedEl.bounds.centerY().toFloat(),
                    )
                    if (tapped) {
                        service.waitForUiChange(prevRev, timeoutMs = 1200L)
                        val freshSnapshot = service.inspectActiveWindow()
                        val stateChanged = freshSnapshot != null && freshSnapshot.revision != prevRev
                        _currentState.set(AutomationState.SUCCEEDED)
                        return AutomationActionResult(
                            success = true,
                            action = "click",
                            target = matchedEl.displayLabel,
                            method = "gesture_coordinate_fallback",
                            packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                            stateChanged = stateChanged,
                            updatedSnapshot = freshSnapshot,
                        )
                    }
                }

                _currentState.set(AutomationState.FAILED)
                return AutomationActionResult(
                    success = false,
                    action = "click",
                    target = target,
                    errorCode = AutomationErrorCodes.TARGET_NOT_FOUND,
                    errorMessage = "Element matching \"$target\" was not found on screen.",
                    retryable = true,
                    updatedSnapshot = snapshot,
                )
            }
            is TargetResolution.Found -> {
                if (!resolution.node.isEnabled) {
                    val snapshot = service.inspectActiveWindow()
                    val result = AutomationActionResult(
                        success = false,
                        action = "click",
                        target = target,
                        errorCode = AutomationErrorCodes.TARGET_DISABLED,
                        errorMessage = "Target element \"$target\" is disabled and cannot be clicked.",
                        updatedSnapshot = snapshot,
                    )
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        kotlin.runCatching { resolution.node.recycle() }
                    }
                    _currentState.set(AutomationState.FAILED)
                    return result
                }

                val prevRev = service.uiRevision
                _currentState.set(AutomationState.WAITING)
                var clicked = false
                try {
                    clicked = service.clickNode(resolution.node)
                } finally {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        kotlin.runCatching { resolution.node.recycle() }
                    }
                }

                // Gesture fallback: read fresh bounds from the live node (not the stale snapshot)
                if (!clicked) {
                    val liveBounds = android.graphics.Rect()
                    resolution.node.getBoundsInScreen(liveBounds)
                    if (!liveBounds.isEmpty) {
                        clicked = service.tapCoordinates(
                            liveBounds.centerX().toFloat(),
                            liveBounds.centerY().toFloat(),
                        )
                    }
                }

                if (clicked) {
                    service.waitForUiChange(prevRev, timeoutMs = 1200L)
                    val freshSnapshot = service.inspectActiveWindow()
                    val stateChanged = freshSnapshot != null && freshSnapshot.revision != prevRev
                    _currentState.set(AutomationState.SUCCEEDED)

                    return AutomationActionResult(
                        success = true,
                        action = "click",
                        target = resolution.snapshot.displayLabel,
                        method = "accessibility_click",
                        packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                        stateChanged = stateChanged,
                        updatedSnapshot = freshSnapshot,
                    )
                } else {
                    _currentState.set(AutomationState.FAILED)
                    return AutomationActionResult(
                        success = false,
                        action = "click",
                        target = target,
                        errorCode = AutomationErrorCodes.ACTION_FAILED,
                        errorMessage = "Found \"$target\" but could not perform click action.",
                        retryable = true,
                        updatedSnapshot = service.inspectActiveWindow(),
                    )
                }
            }
        }
    }

    /**
     * Executes robust text input into an editable field.
     */
    override suspend fun type(
        text: String,
        target: String?,
        submit: Boolean,
        clearFirst: Boolean,
    ): AutomationActionResult {
        _currentState.set(AutomationState.ACTING)
        val service = serviceProvider()
            ?: return AutomationActionResult(
                success = false,
                action = "type",
                target = target,
                errorCode = AutomationErrorCodes.SERVICE_UNAVAILABLE,
                errorMessage = "Accessibility service is unavailable.",
            )

        val targetNode = if (!target.isNullOrBlank()) {
            when (val res = service.resolveTarget(target, mustBeEditable = true)) {
                is TargetResolution.Found -> res.node
                is TargetResolution.Ambiguous -> {
                    _currentState.set(AutomationState.AMBIGUOUS)
                    return AutomationActionResult(
                        success = false,
                        action = "type",
                        target = target,
                        errorCode = AutomationErrorCodes.AMBIGUOUS_TARGET,
                        errorMessage = "Multiple input fields match \"$target\".",
                        candidateMatches = res.matches,
                        updatedSnapshot = service.inspectActiveWindow(),
                    )
                }
                is TargetResolution.NotFound -> {
                    // Try resolving without mustBeEditable (e.g. search icon/bar that needs tap to activate EditText)
                    when (val fallbackRes = service.resolveTarget(target)) {
                        is TargetResolution.Found -> {
                            service.clickNode(fallbackRes.node)
                            delay(400L)
                            service.waitForUiChange(service.uiRevision, timeoutMs = 800L)
                            service.findFocusedInputNode() ?: service.findNodes("").firstOrNull { it.isEditable } ?: fallbackRes.node
                        }
                        else -> {
                            // Check if an editable node already has focus
                            service.findFocusedInputNode()
                        }
                    }
                }
            }
        } else {
            service.findFocusedInputNode() ?: service.findNodes("").firstOrNull { it.isEditable }
        }

        if (targetNode == null) {
            _currentState.set(AutomationState.FAILED)
            return AutomationActionResult(
                success = false,
                action = "type",
                target = target,
                errorCode = AutomationErrorCodes.TARGET_NOT_FOUND,
                errorMessage = "No editable input field found on screen.",
                updatedSnapshot = service.inspectActiveWindow(),
            )
        }

        val prevRev = service.uiRevision
        _currentState.set(AutomationState.WAITING)
        val typed = try {
            service.typeText(targetNode, text, submit = submit, clearFirst = clearFirst)
        } finally {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                kotlin.runCatching { targetNode.recycle() }
            }
        }

        return if (typed) {
            service.waitForUiChange(prevRev, timeoutMs = 1000L)
            val freshSnapshot = service.inspectActiveWindow()
            val stateChanged = freshSnapshot != null && freshSnapshot.revision != prevRev
            _currentState.set(AutomationState.SUCCEEDED)
            AutomationActionResult(
                success = true,
                action = "type",
                target = target ?: "focused_input",
                method = "set_text",
                packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                stateChanged = stateChanged,
                updatedSnapshot = freshSnapshot,
            )
        } else {
            _currentState.set(AutomationState.FAILED)
            AutomationActionResult(
                success = false,
                action = "type",
                target = target,
                errorCode = AutomationErrorCodes.ACTION_FAILED,
                errorMessage = "Failed to type text into input field.",
                retryable = true,
                updatedSnapshot = service.inspectActiveWindow(),
            )
        }
    }

    /**
     * Explicitly submits an input via IME action or Enter.
     */
    override suspend fun submit(target: String?): AutomationActionResult {
        _currentState.set(AutomationState.ACTING)
        val service = serviceProvider()
            ?: return AutomationActionResult(
                success = false,
                action = "submit",
                errorCode = AutomationErrorCodes.SERVICE_UNAVAILABLE,
                errorMessage = "Accessibility service is unavailable.",
            )

        val prevRev = service.uiRevision
        _currentState.set(AutomationState.WAITING)
        val targetNode = if (!target.isNullOrBlank()) {
            when (val res = service.resolveTarget(target)) {
                is TargetResolution.Found -> res.node
                else -> null
            }
        } else service.findFocusedInputNode()

        val submitted = try {
            service.submitInput(targetNode)
        } finally {
            if (targetNode != null && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                kotlin.runCatching { targetNode.recycle() }
            }
        }

        return if (submitted) {
            service.waitForUiChange(prevRev, timeoutMs = 1200L)
            val freshSnapshot = service.inspectActiveWindow()
            val stateChanged = freshSnapshot != null && freshSnapshot.revision != prevRev
            _currentState.set(AutomationState.SUCCEEDED)
            AutomationActionResult(
                success = true,
                action = "submit",
                target = target ?: "focused_input",
                method = "ime_enter",
                packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                stateChanged = stateChanged,
                updatedSnapshot = freshSnapshot,
            )
        } else {
            _currentState.set(AutomationState.FAILED)
            AutomationActionResult(
                success = false,
                action = "submit",
                target = target ?: "focused_input",
                errorCode = AutomationErrorCodes.ACTION_FAILED,
                errorMessage = "Failed to submit input via IME enter.",
                retryable = true,
                updatedSnapshot = service.inspectActiveWindow(),
            )
        }
    }

    /**
     * Scrolls a specific scrollable container or active window.
     */
    override suspend fun scroll(direction: String, targetContainer: String?): AutomationActionResult {
        _currentState.set(AutomationState.ACTING)
        val service = serviceProvider()
            ?: return AutomationActionResult(
                success = false,
                action = "scroll",
                errorCode = AutomationErrorCodes.SERVICE_UNAVAILABLE,
                errorMessage = "Accessibility service is unavailable.",
            )

        val prevRev = service.uiRevision
        _currentState.set(AutomationState.WAITING)
        val scrolled = service.scroll(direction = direction, targetContainerQuery = targetContainer)

        return if (scrolled) {
            delay(350L) // Allow fling physics animation to settle
            service.waitForUiChange(prevRev, timeoutMs = 1000L)
            val freshSnapshot = service.inspectActiveWindow()
            val stateChanged = freshSnapshot != null && freshSnapshot.revision != prevRev
            _currentState.set(AutomationState.SUCCEEDED)
            AutomationActionResult(
                success = true,
                action = "scroll",
                target = direction,
                method = "scroll_$direction",
                packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                stateChanged = stateChanged,
                updatedSnapshot = freshSnapshot,
            )
        } else {
            _currentState.set(AutomationState.FAILED)
            AutomationActionResult(
                success = false,
                action = "scroll",
                target = direction,
                errorCode = AutomationErrorCodes.NOT_SCROLLABLE,
                errorMessage = "Could not scroll the screen or container.",
                updatedSnapshot = service.inspectActiveWindow(),
            )
        }
    }

    /**
     * Dispatches a direct touch swipe gesture from (startX, startY) to (endX, endY).
     */
    override suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): AutomationActionResult {
        _currentState.set(AutomationState.ACTING)
        val service = serviceProvider()
            ?: return AutomationActionResult(
                success = false,
                action = "swipe",
                errorCode = AutomationErrorCodes.SERVICE_UNAVAILABLE,
                errorMessage = "Accessibility service is unavailable.",
            )

        val prevRev = service.uiRevision
        _currentState.set(AutomationState.WAITING)
        val swiped = service.swipe(startX, startY, endX, endY, durationMs = durationMs)

        return if (swiped) {
            delay(300L)
            service.waitForUiChange(prevRev, timeoutMs = 1000L)
            val freshSnapshot = service.inspectActiveWindow()
            val stateChanged = freshSnapshot != null && freshSnapshot.revision != prevRev
            _currentState.set(AutomationState.SUCCEEDED)
            AutomationActionResult(
                success = true,
                action = "swipe",
                target = "($startX,$startY)->($endX,$endY)",
                method = "gesture_swipe",
                packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                stateChanged = stateChanged,
                updatedSnapshot = freshSnapshot,
            )
        } else {
            _currentState.set(AutomationState.FAILED)
            AutomationActionResult(
                success = false,
                action = "swipe",
                errorCode = AutomationErrorCodes.ACTION_FAILED,
                errorMessage = "Failed to dispatch swipe gesture.",
                updatedSnapshot = service.inspectActiveWindow(),
            )
        }
    }

    /**
     * Navigates back.
     */
    suspend fun goBack(): AutomationActionResult {
        _currentState.set(AutomationState.ACTING)
        val service = serviceProvider()
            ?: return AutomationActionResult(
                success = false,
                action = "back",
                errorCode = AutomationErrorCodes.SERVICE_UNAVAILABLE,
                errorMessage = "Accessibility service is unavailable.",
            )

        val prevRev = service.uiRevision
        _currentState.set(AutomationState.WAITING)
        val navigated = service.goBack()

        return if (navigated) {
            service.waitForUiChange(prevRev, timeoutMs = 1000L)
            val freshSnapshot = service.inspectActiveWindow()
            val stateChanged = freshSnapshot != null && freshSnapshot.revision != prevRev
            _currentState.set(AutomationState.SUCCEEDED)
            AutomationActionResult(
                success = true,
                action = "back",
                method = "global_action_back",
                packageName = freshSnapshot?.packageName ?: service.lastObservedPackage,
                stateChanged = stateChanged,
                updatedSnapshot = freshSnapshot,
            )
        } else {
            _currentState.set(AutomationState.FAILED)
            AutomationActionResult(
                success = false,
                action = "back",
                errorCode = AutomationErrorCodes.ACTION_FAILED,
                errorMessage = "Failed to navigate back.",
                updatedSnapshot = service.inspectActiveWindow(),
            )
        }
    }

    /**
     * Verifies postconditions (package name or visible text/element).
     */
    suspend fun verify(
        expected: String? = null,
        expectedPackage: String? = null,
        timeoutMs: Long = 2000L,
    ): AutomationActionResult {
        _currentState.set(AutomationState.VERIFYING)
        val service = serviceProvider()
            ?: return AutomationActionResult(
                success = false,
                action = "verify",
                errorCode = AutomationErrorCodes.SERVICE_UNAVAILABLE,
                errorMessage = "Accessibility service is unavailable.",
            )

        val verified = JarvisAccessibilityService.waitForCondition(timeoutMs = timeoutMs) {
            val pkgMatch = if (!expectedPackage.isNullOrBlank()) {
                val currentPkg = service.lastObservedPackage ?: service.rootInActiveWindow?.packageName?.toString()
                currentPkg?.contains(expectedPackage, ignoreCase = true) == true
            } else true

            val textMatch = if (!expected.isNullOrBlank()) {
                val currentSnapshot = service.inspectActiveWindow()
                currentSnapshot?.elements?.any { it.displayLabel.contains(expected, ignoreCase = true) } == true
            } else true

            pkgMatch && textMatch
        }

        val snapshot = service.inspectActiveWindow()
        return if (verified) {
            _currentState.set(AutomationState.SUCCEEDED)
            AutomationActionResult(
                success = true,
                action = "verify",
                target = expected ?: expectedPackage,
                packageName = snapshot?.packageName ?: service.lastObservedPackage,
                stateChanged = false,
                updatedSnapshot = snapshot,
            )
        } else {
            _currentState.set(AutomationState.FAILED)
            AutomationActionResult(
                success = false,
                action = "verify",
                target = expected ?: expectedPackage,
                errorCode = AutomationErrorCodes.VERIFICATION_FAILED,
                errorMessage = "Verification failed: expected \"${expected ?: expectedPackage}\" was not found on screen.",
                updatedSnapshot = snapshot,
            )
        }
    }

    override suspend fun back(): AutomationActionResult = goBack()

    override suspend fun verify(expected: String, timeoutMs: Long): AutomationActionResult =
        verify(expected = expected, expectedPackage = null, timeoutMs = timeoutMs)

    override suspend fun captureScreenshot(): Bitmap? {
        val service = serviceProvider() ?: return null
        return service.captureScreenshot()
    }
}
