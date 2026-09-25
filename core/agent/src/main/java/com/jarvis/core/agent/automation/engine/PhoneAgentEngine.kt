package com.jarvis.core.agent.automation.engine

import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.DefaultToolPolicy
import com.jarvis.core.agent.ToolPolicy
import com.jarvis.core.agent.automation.AutomationActionResult
import com.jarvis.core.agent.automation.PhoneAgentResult
import com.jarvis.core.agent.automation.UiElementSnapshot
import com.jarvis.core.agent.automation.UiWindowSnapshot
import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.network.ChatRequest
import com.jarvis.core.network.ChatStreamEvent
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

/**
 * Autonomous phone-agent engine modeled after ClosePaw and MobileAgent.
 *
 * Architecture:
 * - OBSERVE: Reads accessibility UI tree.
 * - SAFETY CHECK: Never auto-clicks system permission dialogs; defers to user.
 * - CONFIRMATION GATE: Strictly enforces confirmation for sensitive operations (send, delete, pay, etc.).
 * - DECIDE: Model-driven reasoning (or zero-shot semantic affordance fallback).
 * - ACT: Dispatches atomic primitives (tap, type, submit, scroll, swipe, back) via PhoneAutomationDriver.
 * - WAIT: Waits for event-driven UI change and stabilization.
 * - VERIFY GOAL: Mandates concrete screen postconditions before claiming goal success.
 */
class PhoneAgentEngine(
    private val driver: PhoneAutomationDriver,
    private val llmProvider: (suspend () -> LlmProvider?)? = null,
    private val modelIdProvider: (suspend () -> String)? = null,
    private val toolPolicy: ToolPolicy = DefaultToolPolicy(),
    private val confirmationGate: ConfirmationGate? = null,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val decisionTimeoutMs: Long = DEFAULT_DECISION_TIMEOUT_MS,
    private val overallTimeoutMs: Long = DEFAULT_OVERALL_TIMEOUT_MS,
) {

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val DEFAULT_DECISION_TIMEOUT_MS = 15_000L
        const val DEFAULT_OVERALL_TIMEOUT_MS = 60_000L
        const val MAX_UNCHANGED_SCREENS = 2
        const val MAX_REPEATED_ACTIONS = 2
    }

    sealed class EngineAction {
        data class Click(val target: String, val x: Float? = null, val y: Float? = null) : EngineAction()
        data class Type(val text: String, val target: String? = null, val submit: Boolean = false) : EngineAction()
        data class Submit(val target: String? = null) : EngineAction()
        data class Scroll(val direction: String = "down") : EngineAction()
        data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) : EngineAction()
        object Back : EngineAction()
        data class Done(val summary: String, val postcondition: String? = null) : EngineAction()
        data class Fail(val reason: String, val errorCode: ErrorCode) : EngineAction()
    }

    suspend fun execute(
        goal: String,
        targetApp: String? = null,
        confirmationGate: ConfirmationGate? = null,
        onProgress: (suspend (String) -> Unit)? = null,
    ): PhoneAgentResult {
        currentCoroutineContext().ensureActive()
        val contextGate = currentCoroutineContext()[com.jarvis.core.agent.ConfirmationGateElement]?.gate
        val effectiveGate = confirmationGate ?: contextGate ?: this.confirmationGate

        val trimmedGoal = goal.trim()
        if (trimmedGoal.isBlank()) {
            return PhoneAgentResult.failure("Goal cannot be empty.", ErrorCode.INVALID_TOOL_ARGUMENTS)
        }

        // Verify driver / accessibility readiness
        val initialObs = driver.observe()
        if (initialObs.isFailure) {
            val exMsg = initialObs.exceptionOrNull()?.message.orEmpty()
            val isUnavail = exMsg.contains("unavailable", ignoreCase = true) ||
                exMsg.contains("accessibility", ignoreCase = true)
            return PhoneAgentResult.failure(
                message = if (isUnavail) "Jarvis needs Accessibility access to interact with apps." else exMsg,
                errorCode = if (isUnavail) ErrorCode.ACCESSIBILITY_UNAVAILABLE else ErrorCode.TARGET_NOT_FOUND,
            )
        }

        val completedSteps = mutableListOf<String>()

        return try {
            withTimeout(overallTimeoutMs) {
                runLoop(
                    trimmedGoal = trimmedGoal,
                    targetApp = targetApp,
                    effectiveGate = effectiveGate,
                    completedSteps = completedSteps,
                    onProgress = onProgress,
                )
            }
        } catch (e: TimeoutCancellationException) {
            PhoneAgentResult.failure(
                message = "Phone automation timed out after ${overallTimeoutMs / 1000}s.",
                errorCode = ErrorCode.TIMEOUT,
                completedSteps = completedSteps,
            )
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun runLoop(
        trimmedGoal: String,
        targetApp: String?,
        effectiveGate: ConfirmationGate?,
        completedSteps: MutableList<String>,
        onProgress: (suspend (String) -> Unit)?,
    ): PhoneAgentResult {
        var step = 0
        var previousSignature = ""
        var unchangedScreenCount = 0
        var lastAction: EngineAction? = null
        var repeatedActionCount = 0

        while (step < maxSteps) {
            currentCoroutineContext().ensureActive()
            step++

            // 1. OBSERVE & SETTLE
            var obsSnap = driver.observe().getOrNull()
            if (obsSnap == null || obsSnap.elements.isEmpty()) {
                delay(400L)
                obsSnap = driver.observe().getOrNull()
            }
            if (obsSnap == null || obsSnap.elements.isEmpty()) {
                return PhoneAgentResult.failure(
                    message = "Active window content could not be read.",
                    errorCode = ErrorCode.TARGET_NOT_FOUND,
                    completedSteps = completedSteps,
                )
            }
            var activeSnapshot: UiWindowSnapshot = obsSnap

            // 2. SAFETY CHECK: System Permission Dialogs
            // Never automatically click system permission decisions!
            // Pause execution and require user to grant or dismiss.
            if (activeSnapshot.isPermissionDialog ||
                activeSnapshot.packageName.contains("permissioncontroller", ignoreCase = true) ||
                activeSnapshot.packageName.contains("packageinstaller", ignoreCase = true)
            ) {
                return PhoneAgentResult.failure(
                    message = "A system permission dialog appeared on screen. User action is required to grant or dismiss it.",
                    errorCode = ErrorCode.PERMISSION_REQUIRED,
                    completedSteps = completedSteps,
                )
            }

            // 2b. TARGET APP ENFORCEMENT
            if (!targetApp.isNullOrBlank() && !activeSnapshot.isPermissionDialog) {
                val currentPkg = activeSnapshot.packageName.lowercase(Locale.US)
                val targetLower = targetApp.lowercase(Locale.US)
                val isTargetApp = currentPkg.contains(targetLower) ||
                    (targetLower == "settings" && currentPkg.contains("settings")) ||
                    (targetLower == "youtube" && currentPkg.contains("youtube")) ||
                    (targetLower == "instagram" && currentPkg.contains("instagram")) ||
                    (targetLower == "whatsapp" && currentPkg.contains("whatsapp"))
                val isSystemUi = currentPkg.contains("systemui") || currentPkg.contains("launcher")
                if (!isTargetApp && !isSystemUi && step > 1) {
                    driver.back()
                    delay(400L)
                    val recSnap = driver.observe().getOrNull()
                    if (recSnap != null) {
                        activeSnapshot = recSnap
                    }
                }
            }

            // 3. REPEATED SCREEN DETECTION
            val currentSignature = "${activeSnapshot.packageName}|${activeSnapshot.elements.map { "${it.index}:${it.displayLabel}" }.hashCode()}"
            val isScreenUnchanged = (currentSignature == previousSignature)
            if (isScreenUnchanged) {
                unchangedScreenCount++
                if (unchangedScreenCount >= MAX_UNCHANGED_SCREENS) {
                    if (completedSteps.none { it.startsWith("Scrolled") }) {
                        driver.scroll("down")
                        completedSteps.add("Scrolled down to unstick screen")
                        unchangedScreenCount = 0
                        continue
                    }
                    return PhoneAgentResult.failure(
                        message = "Screen remained unchanged across repeated actions.",
                        errorCode = ErrorCode.LOOP_DETECTED,
                        completedSteps = completedSteps,
                    )
                }
            } else {
                unchangedScreenCount = 0
                previousSignature = currentSignature
            }

            // 4. GOAL VERIFICATION CHECK (Only if actions have already occurred)
            if (completedSteps.isNotEmpty() && verifyGoalReached(trimmedGoal, activeSnapshot, completedSteps)) {
                return PhoneAgentResult.success(
                    message = "Goal achieved: $trimmedGoal.",
                    completedSteps = completedSteps,
                )
            }

            // 5. DECIDE NEXT ACTION (bounded by decisionTimeoutMs)
            val action = try {
                withTimeout(decisionTimeoutMs) {
                    decideNextAction(trimmedGoal, activeSnapshot, completedSteps)
                }
            } catch (e: TimeoutCancellationException) {
                return PhoneAgentResult.failure(
                    message = "Decision timed out after ${decisionTimeoutMs}ms.",
                    errorCode = ErrorCode.TIMEOUT,
                    completedSteps = completedSteps,
                )
            } catch (e: CancellationException) {
                throw e
            }

            // 5b. REPEATED ACTION DETECTION
            if (action == lastAction) {
                repeatedActionCount++
                val isScroll = (action is EngineAction.Scroll)
                val isLoop = if (isScroll) isScreenUnchanged else (isScreenUnchanged || repeatedActionCount >= MAX_REPEATED_ACTIONS)
                if (isLoop) {
                    return PhoneAgentResult.failure(
                        message = "Repeated action detected without progress: ${formatActionSummary(action)}.",
                        errorCode = ErrorCode.LOOP_DETECTED,
                        completedSteps = completedSteps,
                    )
                }
            } else {
                repeatedActionCount = 0
                lastAction = action
            }

            // 6. ACT & WAIT
            when (action) {
                is EngineAction.Done -> {
                    // Mandatory Verification before accepting DONE:
                    return if (verifyGoalReached(trimmedGoal, activeSnapshot, completedSteps, action.postcondition)) {
                        PhoneAgentResult.success(action.summary, completedSteps)
                    } else {
                        PhoneAgentResult.failure(
                            message = "Action sequence completed but concrete postconditions could not be verified on screen.",
                            errorCode = ErrorCode.VERIFICATION_FAILED,
                            completedSteps = completedSteps,
                        )
                    }
                }
                is EngineAction.Fail -> {
                    return PhoneAgentResult.failure(action.reason, action.errorCode, completedSteps)
                }
                is EngineAction.Click -> {
                    val targetElement = activeSnapshot.elements.firstOrNull { it.index.toString() == action.target }
                    val targetLabel = targetElement?.displayLabel ?: action.target
                    val isSensitive = isSensitiveAction("click", action.target, targetElement)
                    if (isSensitive) {
                        if (effectiveGate == null) {
                            return PhoneAgentResult.failure(
                                message = "Action on \"$targetLabel\" requires confirmation, but no confirmation gate is available.",
                                errorCode = ErrorCode.CONFIRMATION_REQUIRED,
                                completedSteps = completedSteps,
                            )
                        }
                        val confirmed = effectiveGate.confirm(
                            toolName = "ui_click",
                            argsJson = """{"target":"$targetLabel","action":"click"}""",
                        )
                        if (!confirmed) {
                            return PhoneAgentResult.failure(
                                message = "Action on \"$targetLabel\" requires user confirmation, which was not granted.",
                                errorCode = ErrorCode.CONFIRMATION_REQUIRED,
                                completedSteps = completedSteps,
                            )
                        }
                    }
                    onProgress?.invoke("Tapping $targetLabel…")
                    val result = driver.tap(target = action.target, x = action.x, y = action.y)
                    if (!result.success) {
                        completedSteps.add("Failed to tap $targetLabel")
                    } else {
                        completedSteps.add("Tapped $targetLabel")
                    }
                }
                is EngineAction.Type -> {
                    val targetElement = if (!action.target.isNullOrBlank()) {
                        activeSnapshot.elements.firstOrNull { it.index.toString() == action.target }
                    } else null
                    val targetLabel = targetElement?.displayLabel ?: action.target
                    val isSensitive = action.submit ||
                        toolPolicy.isSensitiveUiAction("type", action.text) ||
                        isSensitiveAction("type", action.target, targetElement)
                    if (isSensitive) {
                        if (effectiveGate == null) {
                            return PhoneAgentResult.failure(
                                message = "Typing requires confirmation, but no confirmation gate is available.",
                                errorCode = ErrorCode.CONFIRMATION_REQUIRED,
                                completedSteps = completedSteps,
                            )
                        }
                        val confirmed = effectiveGate.confirm(
                            toolName = "ui_type",
                            argsJson = """{"target":"${targetLabel.orEmpty()}","text":"${action.text}"}""",
                        )
                        if (!confirmed) {
                            return PhoneAgentResult.failure(
                                message = "Typing \"${action.text}\" requires user confirmation, which was not granted.",
                                errorCode = ErrorCode.CONFIRMATION_REQUIRED,
                                completedSteps = completedSteps,
                            )
                        }
                    }
                    onProgress?.invoke("Typing \"${action.text}\"…")
                    val result = driver.type(text = action.text, target = action.target, submit = action.submit)
                    if (!result.success) {
                        completedSteps.add("Failed to type \"${action.text}\"")
                    } else {
                        completedSteps.add("Typed \"${action.text}\"")
                    }
                }
                is EngineAction.Submit -> {
                    val targetElement = if (!action.target.isNullOrBlank()) {
                        activeSnapshot.elements.firstOrNull { it.index.toString() == action.target }
                    } else null
                    val targetLabel = targetElement?.displayLabel ?: action.target ?: "submit"
                    val isSensitive = isSensitiveAction("submit", targetLabel, targetElement)
                    if (isSensitive) {
                        if (effectiveGate == null) {
                            return PhoneAgentResult.failure(
                                message = "Submission requires confirmation, but no confirmation gate is available.",
                                errorCode = ErrorCode.CONFIRMATION_REQUIRED,
                                completedSteps = completedSteps,
                            )
                        }
                        val confirmed = effectiveGate.confirm(
                            toolName = "ui_submit",
                            argsJson = """{"target":"$targetLabel"}""",
                        )
                        if (!confirmed) {
                            return PhoneAgentResult.failure(
                                message = "Submission requires user confirmation, which was not granted.",
                                errorCode = ErrorCode.CONFIRMATION_REQUIRED,
                                completedSteps = completedSteps,
                            )
                        }
                    }
                    onProgress?.invoke("Submitting…")
                    val result = driver.submit(target = action.target)
                    if (!result.success) {
                        completedSteps.add("Failed to submit")
                    } else {
                        completedSteps.add("Submitted")
                    }
                }
                is EngineAction.Scroll -> {
                    onProgress?.invoke("Scrolling ${action.direction}…")
                    val result = driver.scroll(action.direction)
                    if (result.success) {
                        completedSteps.add("Scrolled ${action.direction}")
                    }
                }
                is EngineAction.Swipe -> {
                    onProgress?.invoke("Swiping…")
                    val result = driver.swipe(action.startX, action.startY, action.endX, action.endY)
                    if (result.success) {
                        completedSteps.add("Swiped")
                    }
                }
                is EngineAction.Back -> {
                    onProgress?.invoke("Navigating back…")
                    val result = driver.back()
                    if (result.success) {
                        completedSteps.add("Navigated back")
                    }
                }
            }

            // Small stabilization pause between turns
            delay(350L)
        }

        // 7. POST-LOOP FINAL VERIFICATION
        val finalSnapshot = driver.observe().getOrNull()
        return if (finalSnapshot != null && verifyGoalReached(trimmedGoal, finalSnapshot, completedSteps)) {
            PhoneAgentResult.success(
                message = "Goal achieved: $trimmedGoal.",
                completedSteps = completedSteps,
            )
        } else {
            PhoneAgentResult.failure(
                message = "Goal could not be completed within step limit ($maxSteps steps).",
                errorCode = ErrorCode.STEP_LIMIT_REACHED,
                completedSteps = completedSteps,
            )
        }
    }

    private fun isSensitiveAction(actionName: String, target: String?, element: UiElementSnapshot?): Boolean {
        val candidates = listOfNotNull(
            target,
            element?.displayLabel,
            element?.text,
            element?.contentDescription,
            element?.viewId,
        )
        return candidates.any { toolPolicy.isSensitiveUiAction(actionName, it) }
    }

    private fun formatActionSummary(action: EngineAction): String = when (action) {
        is EngineAction.Click -> "Click(${action.target})"
        is EngineAction.Type -> "Type(\"${action.text}\", target=${action.target})"
        is EngineAction.Submit -> "Submit(${action.target})"
        is EngineAction.Scroll -> "Scroll(${action.direction})"
        is EngineAction.Swipe -> "Swipe"
        is EngineAction.Back -> "Back"
        is EngineAction.Done -> "Done"
        is EngineAction.Fail -> "Fail"
    }

    /**
     * Goal verification: Requires concrete screen postconditions.
     * Never declare success based solely on step count, fuzzy word splitting, or action dispatch!
     */
    fun verifyGoalReached(
        goal: String,
        snapshot: UiWindowSnapshot,
        completedSteps: List<String>,
        explicitPostcondition: String? = null,
    ): Boolean {
        if (completedSteps.isEmpty()) return false

        val visibleTexts = snapshot.elements.mapNotNull { it.text?.trim()?.lowercase(Locale.US) }
        val visibleDescs = snapshot.elements.mapNotNull { it.contentDescription?.trim()?.lowercase(Locale.US) }
        val allContent = visibleTexts + visibleDescs + listOfNotNull(snapshot.windowTitle?.lowercase(Locale.US))

        // 1. Explicit postcondition verification if provided
        if (!explicitPostcondition.isNullOrBlank()) {
            val targetLower = explicitPostcondition.lowercase(Locale.US)
            return allContent.any { it.contains(targetLower) } || snapshot.packageName.contains(targetLower)
        }

        val lowerGoal = goal.lowercase(Locale.US)

        // 2. Concrete Search verification: query entered/submitted AND query or search results present on screen
        val isSearchGoal = lowerGoal.contains("search") || lowerGoal.contains("find")
        if (isSearchGoal) {
            val query = extractQueryTerms(goal)
            if (query.isNotBlank()) {
                val queryLower = query.lowercase(Locale.US)
                val queryPresentOnScreen = allContent.any { it.contains(queryLower) }
                val hasInteractedWithQuery = completedSteps.any {
                    (it.contains("Typed", ignoreCase = true) && it.contains(query, ignoreCase = true)) ||
                        it.contains("Submitted", ignoreCase = true) ||
                        it.contains("Tapped", ignoreCase = true)
                }

                return hasInteractedWithQuery && queryPresentOnScreen
            }
        }

        // 3. Settings / Toggle verification: target setting screen visible or toggle state present
        if (lowerGoal.contains("settings") || lowerGoal.contains("wi-fi") || lowerGoal.contains("wifi") || lowerGoal.contains("bluetooth")) {
            val isSettingsPkg = snapshot.packageName.lowercase(Locale.US).contains("settings")
            val targetMentioned = (lowerGoal.contains("wi-fi") || lowerGoal.contains("wifi")) && allContent.any { it.contains("wi-fi") || it.contains("wifi") } ||
                (lowerGoal.contains("bluetooth") && allContent.any { it.contains("bluetooth") })
            if (isSettingsPkg || targetMentioned) return true
        }

        // 4. App launch verification: target app in foreground
        val prefixes = listOf("open ", "launch ", "go to ")
        for (prefix in prefixes) {
            if (lowerGoal.startsWith(prefix)) {
                val appNoun = lowerGoal.substring(prefix.length).trim()
                if (appNoun.isNotBlank() && snapshot.packageName.lowercase(Locale.US).contains(appNoun)) {
                    return true
                }
            }
        }

        return false
    }

    private suspend fun decideNextAction(
        goal: String,
        snapshot: UiWindowSnapshot,
        completedSteps: List<String>,
    ): EngineAction {
        val contextProvider = currentCoroutineContext()[com.jarvis.core.agent.LlmProviderElement]?.provider
        val contextModelId = currentCoroutineContext()[com.jarvis.core.agent.LlmProviderElement]?.modelId

        val provider = contextProvider ?: llmProvider?.invoke()
        val modelId = contextModelId ?: modelIdProvider?.invoke()

        if (provider != null && !modelId.isNullOrBlank()) {
            val llmAction = decideViaLlm(provider, modelId, goal, snapshot, completedSteps)
            if (llmAction != null) return llmAction
        }

        return decideViaAffordances(goal, snapshot, completedSteps)
    }

    /**
     * MobileAgent zero-shot LLM reasoning layer.
     */
    private suspend fun decideViaLlm(
        provider: LlmProvider,
        modelId: String,
        goal: String,
        snapshot: UiWindowSnapshot,
        completedSteps: List<String>,
    ): EngineAction? {
        return try {
            val elementsSummary = snapshot.elements.take(25).joinToString("\n") { el ->
                "[${el.index}] ${el.type} \"${el.displayLabel}\" (id: ${el.viewId ?: "none"}, bounds: ${el.bounds?.toShortString() ?: "unknown"})"
            }

            val prompt = """
                You are an autonomous Android mobile agent.
                Goal: "$goal"
                Active App: ${snapshot.packageName}
                Window: ${snapshot.windowTitle ?: "unknown"}
                Interactive UI Elements:
                $elementsSummary
                
                Action History:
                ${if (completedSteps.isEmpty()) "None" else completedSteps.joinToString("\n")}
                
                Choose the single best next action.
                Output strict JSON only with no formatting:
                {"action":"click"|"type"|"submit"|"scroll"|"back"|"done"|"fail","target":"<index or text>","text":"<text to type>","submit":false,"direction":"down","postcondition":"<exact text or element required on screen to confirm success>","reason":"<brief explanation>"}
            """.trimIndent()

            val request = ChatRequest(
                conversationHistory = listOf(
                    Message(
                        conversationId = "phone_agent",
                        role = MessageRole.USER,
                        content = prompt,
                    ),
                ),
                model = modelId,
            )

            val responseBuilder = StringBuilder()
            provider.streamChat(request).collect { event ->
                if (event is ChatStreamEvent.TokenDelta) {
                    responseBuilder.append(event.text)
                }
            }

            val raw = responseBuilder.toString().trim()
            val startIdx = raw.indexOf('{')
            val endIdx = raw.lastIndexOf('}')
            if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
                return null
            }
            val jsonStr = raw.substring(startIdx, endIdx + 1)
            val json = JSONObject(jsonStr)

            when (json.optString("action").lowercase(Locale.US)) {
                "click", "tap" -> {
                    val target = json.optString("target")
                    if (target.isNotBlank()) EngineAction.Click(target) else null
                }
                "type" -> {
                    val text = json.optString("text")
                    val target = json.optString("target").takeIf { it.isNotBlank() }
                    val submit = json.optBoolean("submit", false)
                    if (text.isNotBlank()) EngineAction.Type(text, target, submit) else null
                }
                "submit" -> {
                    val target = json.optString("target").takeIf { it.isNotBlank() }
                    EngineAction.Submit(target)
                }
                "scroll" -> EngineAction.Scroll(json.optString("direction", "down"))
                "back" -> EngineAction.Back
                "done" -> {
                    val postcondition = json.optString("postcondition").takeIf { it.isNotBlank() }
                    EngineAction.Done(json.optString("reason", "Goal completed."), postcondition)
                }
                "fail" -> EngineAction.Fail(json.optString("reason", "Could not complete action."), ErrorCode.ACTION_FAILED)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Zero-shot semantic affordance decision when offline or without LLM.
     * Uses semantic element roles (text_input, button, contentDescription) without app-specific rules.
     */
    private fun decideViaAffordances(
        goal: String,
        snapshot: UiWindowSnapshot,
        completedSteps: List<String>,
    ): EngineAction {
        val lowerGoal = goal.lowercase(Locale.US)

        // 1. Search Affordance: Locate search button or input field
        if (lowerGoal.contains("search") || lowerGoal.contains("find")) {
            val query = extractQueryTerms(goal)

            // If an editable field is already visible and text hasn't been entered
            val editable = snapshot.elements.firstOrNull { it.isEditable }
            if (editable != null && completedSteps.none { it.startsWith("Typed") }) {
                return EngineAction.Type(
                    text = query.ifBlank { goal },
                    target = editable.index.toString(),
                    submit = false,
                )
            }

            // If query was typed but not yet submitted
            if (completedSteps.any { it.startsWith("Typed") } && completedSteps.none { it.startsWith("Submitted") }) {
                return EngineAction.Submit(editable?.index?.toString())
            }

            // Look for a search icon / button affordance
            val searchBtn = snapshot.elements.firstOrNull { el ->
                val label = el.displayLabel.lowercase(Locale.US)
                val id = el.viewId?.lowercase(Locale.US).orEmpty()
                label == "search" || label.contains("search") || label.contains("find") ||
                    id.contains("search") || id.contains("find") || id.contains("query")
            }

            if (searchBtn != null && completedSteps.none { it.contains("Tapped") }) {
                return EngineAction.Click(searchBtn.index.toString())
            }
        }

        // 2. Generic Navigation Target Affordance: Match key targets in goal
        val goalWords = lowerGoal.split(" ")
            .filter { it.length > 2 && it !in setOf("open", "go", "to", "the", "and", "in", "on", "into", "click", "tap") }

        for (word in goalWords) {
            val matchingEl = snapshot.elements.firstOrNull { el ->
                el.displayLabel.lowercase(Locale.US).contains(word) && el.isClickable
            }
            if (matchingEl != null && completedSteps.none { it.contains(matchingEl.displayLabel) }) {
                return EngineAction.Click(matchingEl.index.toString())
            }
        }

        // 3. Fallback: Scroll down to reveal more items if available
        return EngineAction.Scroll("down")
    }

    private fun extractQueryTerms(goal: String): String {
        val lower = goal.lowercase(Locale.US)
        val prefixes = listOf("search for ", "search ", "find ", "look for ", "query ")
        for (prefix in prefixes) {
            val idx = lower.indexOf(prefix)
            if (idx != -1) {
                var query = goal.substring(idx + prefix.length).trim()
                // Strip trailing preposition like "in youtube", "on instagram"
                val prepIdx = query.lastIndexOf(" in ", ignoreCase = true)
                    .takeIf { it != -1 } ?: query.lastIndexOf(" on ", ignoreCase = true)
                if (prepIdx != -1) {
                    query = query.substring(0, prepIdx).trim()
                }
                return query.trim('\"', '\'', ' ')
            }
        }
        return ""
    }
}
