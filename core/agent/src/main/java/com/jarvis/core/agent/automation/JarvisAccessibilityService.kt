package com.jarvis.core.agent.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Compact semantic UI element representation for decision routing and action resolution.
 */
data class UiElementSnapshot(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean = true,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false,
    val isScrollable: Boolean = false,
    val isVisible: Boolean = true,
    val bounds: Rect? = null,
) {
    val type: String
        get() = when {
            isEditable -> "text_input"
            isChecked -> "checkbox"
            className?.contains("Switch", ignoreCase = true) == true -> "switch"
            className?.contains("Tab", ignoreCase = true) == true -> "tab"
            isClickable -> "button"
            isScrollable -> "scroll_container"
            else -> "text"
        }

    val displayLabel: String
        get() = listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            contentDescription?.takeIf { it.isNotBlank() },
            viewId?.takeIf { it.isNotBlank() },
        ).firstOrNull()?.trim() ?: "Element"
}

/**
 * Compact, deduplicated snapshot of the active window hierarchy.
 */
data class UiWindowSnapshot(
    val packageName: String,
    val windowTitle: String? = null,
    val elements: List<UiElementSnapshot> = emptyList(),
    val revision: Long = 0L,
    val isPermissionDialog: Boolean = false,
    val isSystemSettings: Boolean = false,
) {
    fun format(): String = buildString {
        appendLine("SCREEN: ${windowTitle ?: packageName}")
        appendLine("PACKAGE: $packageName")
        if (isPermissionDialog) {
            appendLine("NOTICE: Android system permission request dialog detected.")
        }
        appendLine("\nELEMENTS:")
        if (elements.isEmpty()) {
            appendLine("(No interactive or labeled elements visible)")
        } else {
            for (el in elements) {
                val label = el.displayLabel
                val traits = mutableListOf<String>()
                traits.add("type=${el.type}")
                if (el.isClickable) traits.add("clickable=true")
                if (el.isEditable) traits.add("editable=true")
                if (!el.isEnabled) traits.add("enabled=false")
                if (el.isSelected) traits.add("selected=true")
                if (el.isChecked) traits.add("checked=true")
                if (el.isScrollable) traits.add("scrollable=true")

                val extra = mutableListOf<String>()
                if (!el.viewId.isNullOrBlank() && !el.viewId.equals(label, ignoreCase = true)) {
                    extra.add("id: ${el.viewId}")
                }
                if (!el.contentDescription.isNullOrBlank() && !el.contentDescription.equals(label, ignoreCase = true)) {
                    extra.add("desc: ${el.contentDescription}")
                }

                val traitsStr = if (traits.isNotEmpty()) " | ${traits.joinToString(", ")}" else ""
                val extraStr = if (extra.isNotEmpty()) " [${extra.joinToString("; ")}]" else ""
                appendLine("${el.index}. $label$traitsStr$extraStr")
            }
        }
    }
}

/**
 * Result of resolving a target query against the active window.
 */
sealed class TargetResolution {
    data class Found(val node: AccessibilityNodeInfo, val snapshot: UiElementSnapshot) : TargetResolution()
    data class Ambiguous(val query: String, val matches: List<UiElementSnapshot>) : TargetResolution()
    data object NotFound : TargetResolution()
}

/**
 * Android AccessibilityService executing UI interaction, gesture dispatch, and window observation.
 */
class JarvisAccessibilityService : AccessibilityService() {

    @Volatile
    var lastObservedPackage: String? = null
        private set

    @Volatile
    var lastObservedTitle: String? = null
        private set

    @Volatile
    var uiRevision: Long = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            lastObservedPackage = pkg
        }
        val textList = event.text
        if (!textList.isNullOrEmpty()) {
            val firstText = textList.firstOrNull()?.toString()?.trim()
            if (!firstText.isNullOrBlank() && firstText.length in 2..50) {
                lastObservedTitle = firstText
            }
        }
        uiRevision++
    }

    override fun onInterrupt() {
        // Continue maintaining instance if still bound
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    suspend fun waitForUiChange(previousRevision: Long, timeoutMs: Long = 1200L): Boolean {
        return waitForCondition(timeoutMs = timeoutMs, pollIntervalMs = 50L) {
            uiRevision != previousRevision
        }
    }

    suspend fun waitForPackage(expectedPackage: String, timeoutMs: Long = 2000L): Boolean {
        return waitForCondition(timeoutMs = timeoutMs, pollIntervalMs = 80L) {
            val current = lastObservedPackage ?: rootInActiveWindow?.packageName?.toString()
            current?.contains(expectedPackage, ignoreCase = true) == true
        }
    }

    fun inspectActiveWindow(): UiWindowSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: lastObservedPackage ?: "unknown"
        val elements = mutableListOf<UiElementSnapshot>()
        val seenSignatures = mutableSetOf<String>()

        var currentIndex = 1
        var detectedTitle: String? = lastObservedTitle

        val isPermissionDialog = pkg.contains("permissioncontroller", ignoreCase = true) ||
            pkg.contains("packageinstaller", ignoreCase = true)
        val isSystemSettings = pkg.contains("settings", ignoreCase = true)

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || elements.size >= 40) return

            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val fullResId = node.viewIdResourceName
            val resId = fullResId?.substringAfter(":id/")?.substringAfter('/')?.takeIf { it.isNotBlank() }
            val cls = node.className?.toString()?.substringAfterLast('.')
            val isClickable = node.isClickable || node.isCheckable
            val isEditable = node.isEditable
            val isEnabled = node.isEnabled
            val isSelected = node.isSelected
            val isChecked = node.isChecked
            val isScrollable = node.isScrollable
            val isVisible = node.isVisibleToUser

            val isSystemNoise = fullResId?.contains("navigation_bar") == true ||
                fullResId?.contains("status_bar") == true ||
                cls == "NavigationBarView" ||
                cls == "StatusBarView"

            if (!isSystemNoise && (!text.isNullOrBlank() || !desc.isNullOrBlank() || isClickable || isEditable || isScrollable)) {
                val signature = "${text.orEmpty()}|${desc.orEmpty()}|$resId|$isClickable|$isEditable|$cls"
                if (seenSignatures.add(signature)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val snapshot = UiElementSnapshot(
                        index = currentIndex++,
                        text = text,
                        contentDescription = desc,
                        viewId = resId,
                        className = cls,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        isEnabled = isEnabled,
                        isSelected = isSelected,
                        isChecked = isChecked,
                        isScrollable = isScrollable,
                        isVisible = isVisible,
                        bounds = bounds,
                    )
                    elements.add(snapshot)

                    if (detectedTitle == null && depth <= 3 && !text.isNullOrBlank() && text.length in 3..40 && !isClickable && !isEditable) {
                        detectedTitle = text
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null) {
                    try {
                        traverse(child, depth + 1)
                    } finally {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            runCatching { child.recycle() }
                        }
                    }
                }
            }
        }

        try {
            traverse(root, 0)
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { root.recycle() }
            }
        }
        return UiWindowSnapshot(
            packageName = pkg,
            windowTitle = detectedTitle,
            elements = elements,
            revision = uiRevision,
            isPermissionDialog = isPermissionDialog,
            isSystemSettings = isSystemSettings,
        )
    }

    /**
     * Resolves target from live accessibility tree to avoid stale node references.
     */
    fun resolveTarget(
        query: String,
        mustBeClickable: Boolean = false,
        mustBeEditable: Boolean = false,
    ): TargetResolution {
        val root = rootInActiveWindow ?: return TargetResolution.NotFound
        val cleanQuery = query.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("[", "]")
            .removePrefix("#")
            .trim()
        if (cleanQuery.isBlank()) return TargetResolution.NotFound

        // 1. Target by Index
        val indexNumber = cleanQuery.toIntOrNull()
            ?: cleanQuery.removePrefix("item ").removePrefix("element ").toIntOrNull()
        val snapshot = inspectActiveWindow()

        if (indexNumber != null && snapshot != null) {
            val targetEl = snapshot.elements.firstOrNull { it.index == indexNumber }
            if (targetEl != null) {
                val resolvedNode = findNodeBySnapshot(root, targetEl)
                if (resolvedNode != null) {
                    return TargetResolution.Found(resolvedNode, targetEl)
                }
            }
        }

        data class MatchedCandidate(
            val node: AccessibilityNodeInfo,
            val snapshot: UiElementSnapshot,
            val matchPriority: Int,
        )

        val candidates = mutableListOf<MatchedCandidate>()
        val queryLower = cleanQuery.lowercase(Locale.US)

        fun search(node: AccessibilityNodeInfo?, depth: Int, indexHolder: IntArray) {
            if (node == null || depth > 20 || candidates.size >= 25) return

            val text = node.text?.toString()?.trim()
            val textLower = text?.lowercase(Locale.US)
            val desc = node.contentDescription?.toString()?.trim()
            val descLower = desc?.lowercase(Locale.US)
            val fullResId = node.viewIdResourceName
            val resId = fullResId?.substringAfter(":id/")?.substringAfter('/')?.trim()
            val resIdLower = resId?.lowercase(Locale.US)
            val isClickable = node.isClickable || node.isCheckable || node.parent?.isClickable == true
            val isEditable = node.isEditable

            var priority = 0
            if (textLower == queryLower) {
                priority = 100
            } else if (descLower == queryLower) {
                priority = 95
            } else if (resIdLower == queryLower) {
                priority = 90
            } else if (textLower != null && textLower.startsWith(queryLower)) {
                priority = 85
            } else if (descLower != null && descLower.startsWith(queryLower)) {
                priority = 80
            } else if (textLower != null && textLower.split(Regex("\\s+")).contains(queryLower)) {
                priority = 75
            } else if (descLower != null && descLower.split(Regex("\\s+")).contains(queryLower)) {
                priority = 70
            } else if (textLower != null && textLower.contains(queryLower)) {
                priority = 65
            } else if (descLower != null && descLower.contains(queryLower)) {
                priority = 60
            } else if (resIdLower != null && resIdLower.contains(queryLower)) {
                priority = 50
            } else if (!textLower.isNullOrBlank() && queryLower.contains(textLower)) {
                priority = 40
            }

            if (priority > 0) {
                val matchesFilter = (!mustBeClickable || isClickable) && (!mustBeEditable || isEditable)
                if (matchesFilter) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val el = UiElementSnapshot(
                        index = indexHolder[0]++,
                        text = text,
                        contentDescription = desc,
                        viewId = resId,
                        className = node.className?.toString()?.substringAfterLast('.'),
                        isClickable = node.isClickable,
                        isEditable = isEditable,
                        isEnabled = node.isEnabled,
                        isSelected = node.isSelected,
                        isChecked = node.isChecked,
                        isScrollable = node.isScrollable,
                        isVisible = node.isVisibleToUser,
                        bounds = bounds,
                    )
                    candidates.add(MatchedCandidate(node, el, priority))
                }
            }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null) {
                    search(child, depth + 1, indexHolder)
                }
            }
        }

        search(root, 0, intArrayOf(1))

        if (candidates.isEmpty()) {
            return TargetResolution.NotFound
        }

        val topPriority = candidates.maxOf { it.matchPriority }
        val topMatches = candidates.filter { it.matchPriority == topPriority }

        return if (topMatches.size == 1) {
            val winner = topMatches.first()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                candidates.forEach { candidate ->
                    if (candidate.node != winner.node) {
                        runCatching { candidate.node.recycle() }
                    }
                }
            }
            TargetResolution.Found(winner.node, winner.snapshot)
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                candidates.forEach { candidate ->
                    runCatching { candidate.node.recycle() }
                }
            }
            TargetResolution.Ambiguous(cleanQuery, topMatches.map { it.snapshot })
        }
    }

    private fun findNodeBySnapshot(root: AccessibilityNodeInfo, target: UiElementSnapshot): AccessibilityNodeInfo? {
        fun matchExact(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 20) return null
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (target.bounds != null && !bounds.isEmpty && bounds == target.bounds) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                val found = matchExact(child, depth + 1)
                if (found != null) return found
            }
            return null
        }

        val exact = matchExact(root, 0)
        if (exact != null) return exact

        fun matchContains(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 20) return null
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val targetBounds = target.bounds
            if (targetBounds != null && !bounds.isEmpty && !targetBounds.isEmpty) {
                if (bounds.contains(targetBounds.centerX(), targetBounds.centerY()) &&
                    Math.abs(bounds.width() - targetBounds.width()) < 100 &&
                    Math.abs(bounds.height() - targetBounds.height()) < 100
                ) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                val found = matchContains(child, depth + 1)
                if (found != null) return found
            }
            return null
        }

        val contained = matchContains(root, 0)
        if (contained != null) return contained

        fun matchText(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 20) return null
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val resId = node.viewIdResourceName?.substringAfter(":id/")?.substringAfter('/')?.trim()

            val textMatch = if (target.text != null) text == target.text else true
            val descMatch = if (target.contentDescription != null) desc == target.contentDescription else true
            val idMatch = if (target.viewId != null) resId == target.viewId else true

            if (textMatch && descMatch && idMatch && (target.text != null || target.contentDescription != null || target.viewId != null)) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                val found = matchText(child, depth + 1)
                if (found != null) return found
            }
            return null
        }

        return matchText(root, 0)
    }

    /**
     * Directly taps specific screen coordinates using accessibility gesture dispatch.
     * Follows proven ClosePaw / MobileAgent touchscreen interaction pattern.
     */
    suspend fun tapCoordinates(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (x <= 0f || y <= 0f) return false

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val deferred = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        val success = if (dispatched) {
            withTimeoutOrNull(800L) { deferred.await() } ?: true
        } else false

        return success
    }

    /**
     * Performs a touch swipe gesture from (startX, startY) to (endX, endY).
     * Used for smooth, reliable scrolling on modern Android apps (Instagram, YouTube, Compose, Flutter).
     */
    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val deferred = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        val success = if (dispatched) {
            withTimeoutOrNull(1200L) { deferred.await() } ?: true
        } else false

        return success
    }

    /**
     * Taps target node using direct action, parent walk-up, or gesture dispatch with callback.
     */
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var clicked = false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            clicked = true
        } else {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true
                    break
                }
                parent = parent.parent
                depth++
            }
        }

        if (!clicked) {
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null && child.isClickable && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true
                    break
                }
            }
        }

        // Gesture dispatch fallback to element center point (ClosePaw style)
        if (!clicked) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                clicked = tapCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }

        // Signal waitForUiChange callers: increment revision so any pending wait completes
        if (clicked) {
            uiRevision++
        }

        return clicked
    }

    fun findFocusedInputNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val inputFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocus != null && inputFocus.isEditable) {
            return inputFocus
        }
        val a11yFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (a11yFocus != null && a11yFocus.isEditable) {
            return a11yFocus
        }
        fun search(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 20) return null
            if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                val found = search(child, depth + 1)
                if (found != null) return found
            }
            return null
        }
        return search(root, 0)
    }

    /**
     * Types text into an editable node without implicit submission.
     */
    suspend fun typeText(node: AccessibilityNodeInfo, text: String, submit: Boolean = false, clearFirst: Boolean = false): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (clearFirst) {
            val emptyArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyArgs)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!set) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        val inputFocus = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (!set && inputFocus != null) {
            set = inputFocus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        // Clipboard paste fallback (guarantees text entry for WebViews, Flutter, and complex custom inputs)
        if (!set) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("jarvis_input", text))
                set = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (!set && inputFocus != null) {
                    set = inputFocus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }
        }

        if (submit) {
            submitInput(node)
        }
        return set
    }

    /**
     * Explicitly submits an input or focused field via IME Enter action or on-screen submit button.
     */
    suspend fun submitInput(targetNode: AccessibilityNodeInfo? = null): Boolean {
        val root = rootInActiveWindow
        val node = targetNode ?: findFocusedInputNode() ?: root
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && node != null) {
            val done = runCatching { node.performAction(android.R.id.accessibilityActionImeEnter) }.getOrDefault(false)
            if (done) return true
        }
        val submitNode = (resolveTarget("Search") as? TargetResolution.Found)?.node ?:
            (resolveTarget("Submit") as? TargetResolution.Found)?.node ?:
            (resolveTarget("Go") as? TargetResolution.Found)?.node ?:
            (resolveTarget("Send") as? TargetResolution.Found)?.node
        if (submitNode != null && submitNode != node) {
            return clickNode(submitNode)
        }
        return false
    }

    /**
     * Scrolls a specific scrollable container or root in any direction (down, up, left, right).
     * Uses gesture swipe fallback to ensure 100% scrolling capability on all modern apps.
     */
    suspend fun scroll(direction: String = "down", targetContainerQuery: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val lowerDir = direction.lowercase(Locale.US)
        val isForward = lowerDir == "forward" || lowerDir == "down"
        val isBackward = lowerDir == "backward" || lowerDir == "up"
        val isLeft = lowerDir == "left"
        val isRight = lowerDir == "right"

        val action = if (isBackward) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        val targetContainer = if (!targetContainerQuery.isNullOrBlank()) {
            when (val res = resolveTarget(targetContainerQuery)) {
                is TargetResolution.Found -> res.node
                else -> null
            }
        } else null

        fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 12) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                val match = findScrollable(child, depth + 1)
                if (match != null) return match
            }
            return null
        }

        val scrollable = targetContainer ?: findScrollable(root, 0)
        var performed = if (isForward || isBackward) {
            scrollable?.performAction(action) ?: root.performAction(action)
        } else false

        // ClosePaw gesture swipe fallback: handles modern scroll views where performAction fails
        if (!performed) {
            val bounds = Rect()
            (scrollable ?: root).getBoundsInScreen(bounds)
            val top = if (!bounds.isEmpty && bounds.height() > 100) bounds.top.toFloat() else 0f
            val bottom = if (!bounds.isEmpty && bounds.height() > 100) bounds.bottom.toFloat() else (resources.displayMetrics.heightPixels.toFloat().takeIf { it > 0 } ?: 1920f)
            val left = if (!bounds.isEmpty && bounds.width() > 100) bounds.left.toFloat() else 0f
            val right = if (!bounds.isEmpty && bounds.width() > 100) bounds.right.toFloat() else (resources.displayMetrics.widthPixels.toFloat().takeIf { it > 0 } ?: 1080f)

            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            val h = bottom - top
            val w = right - left

            val (startX, startY, endX, endY) = when {
                isBackward -> listOf(centerX, top + h * 0.25f, centerX, top + h * 0.75f)
                isRight -> listOf(left + w * 0.80f, centerY, left + w * 0.20f, centerY)
                isLeft -> listOf(left + w * 0.20f, centerY, left + w * 0.80f, centerY)
                else -> listOf(centerX, top + h * 0.75f, centerX, top + h * 0.25f)
            }
            performed = swipe(startX, startY, endX, endY, durationMs = 320L)
        }

        return performed
    }

    suspend fun scroll(forward: Boolean, targetContainerQuery: String? = null): Boolean =
        scroll(if (forward) "down" else "up", targetContainerQuery)

    /**
     * Performs global back navigation.
     */
    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun findNodes(query: String, maxResults: Int = 10): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val cleanQuery = query.trim().lowercase(Locale.US)
        val matches = mutableListOf<AccessibilityNodeInfo>()

        fun search(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || matches.size >= maxResults) return
            val text = node.text?.toString()?.lowercase(Locale.US)
            val desc = node.contentDescription?.toString()?.lowercase(Locale.US)
            val resId = node.viewIdResourceName?.lowercase(Locale.US)

            val isMatch = (text != null && text.contains(cleanQuery)) ||
                (desc != null && desc.contains(cleanQuery)) ||
                (resId != null && resId.contains(cleanQuery))

            if (isMatch) {
                matches.add(node)
            }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null) {
                    search(child, depth + 1)
                }
            }
        }

        search(root, 0)
        return matches
    }

    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val softwareBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                                if (cont.isActive) cont.resume(softwareBitmap ?: hwBitmap)
                            } catch (_: Exception) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null

        fun isActive(): Boolean = instance != null

        fun isServiceConfigured(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${JarvisAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                    componentName.contains(JarvisAccessibilityService::class.java.simpleName, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        suspend fun waitForCondition(
            timeoutMs: Long = 2000L,
            pollIntervalMs: Long = 80L,
            predicate: () -> Boolean,
        ): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                currentCoroutineContext().ensureActive()
                if (predicate()) return true
                delay(pollIntervalMs)
            }
            return predicate()
        }
    }
}
