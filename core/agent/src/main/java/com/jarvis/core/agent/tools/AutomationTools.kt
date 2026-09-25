package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.agent.automation.AutomationController
import com.jarvis.core.agent.automation.DefaultPhoneAgent
import com.jarvis.core.agent.automation.JarvisAccessibilityService
import com.jarvis.core.agent.automation.PhoneAgent
import com.jarvis.core.common.PermissionTier

/**
 * AutomationTools registry.
 *
 * Exposes a single public capability to the agent runtime:
 * - phone_agent(goal)
 *
 * Internal primitives (observe, click, type, scroll, swipe, back, verify) are
 * retained for driver execution and testing, but not exposed to the LLM.
 */
object AutomationTools {
    const val PHONE_AGENT = PhoneAgentTool.NAME

    // Internal primitives
    const val UI_OBSERVE = "ui_observe"
    const val UI_CLICK = "ui_click"
    const val UI_TYPE = "ui_type"
    const val UI_SUBMIT = "ui_submit"
    const val UI_SCROLL = "ui_scroll"
    const val UI_SWIPE = "ui_swipe"
    const val UI_BACK = "ui_back"
    const val UI_VERIFY = "ui_verify"

    /**
     * The single public capability manifest exposed to the AgentRunner / LLM.
     */
    val manifestNames: List<String> = listOf(PHONE_AGENT)

    /**
     * Registers tools for AgentRunner. Only exposes phone_agent to the LLM.
     */
    fun all(
        phoneAgent: PhoneAgent? = null,
        launchApp: (suspend (String) -> Result<Unit>)? = null,
        serviceProvider: () -> JarvisAccessibilityService? = { JarvisAccessibilityService.instance },
        llmProvider: (suspend () -> com.jarvis.core.network.LlmProvider?)? = null,
        modelIdProvider: (suspend () -> String)? = null,
        controller: AutomationController = AutomationController(serviceProvider),
        confirmationGate: com.jarvis.core.agent.ConfirmationGate? = null,
    ): List<Tool> = buildList {
        val resolvedPhoneAgent = phoneAgent ?: if (launchApp != null) {
            DefaultPhoneAgent(
                serviceProvider = serviceProvider,
                launchApp = launchApp,
                llmProvider = llmProvider,
                modelIdProvider = modelIdProvider,
                driver = controller,
                confirmationGate = confirmationGate,
            )
        } else null

        if (resolvedPhoneAgent != null) {
            add(PhoneAgentTool(resolvedPhoneAgent, confirmationGate))
        }
    }

    /**
     * Internal primitives for direct driver execution or unit testing.
     */
    fun internalPrimitives(controller: AutomationController): List<Tool> = listOf(
        uiObserve(controller),
        uiClick(controller),
        uiType(controller),
        uiSubmit(controller),
        uiScroll(controller),
        uiSwipe(controller),
        uiBack(controller),
        uiVerify(controller),
    )

    fun uiObserve(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_OBSERVE
            override val description =
                "Observe the active Android window and retrieve visible UI elements (text, buttons, inputs) with index numbers."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val obs = controller.observe()
                return if (obs.isSuccess) {
                    val snapshot = obs.getOrThrow()
                    ToolResult(
                        success = true,
                        observationText = snapshot.format(),
                        structuredData = mapOf(
                            "action" to "observe",
                            "package_name" to snapshot.packageName,
                            "window_title" to (snapshot.windowTitle ?: ""),
                            "element_count" to snapshot.elements.size,
                        ),
                    )
                } else {
                    val err = obs.exceptionOrNull()?.message ?: "Failed to observe screen"
                    ToolResult(
                        success = false,
                        observationText = "Screen observation failed: $err",
                        error = com.jarvis.core.agent.automation.AutomationErrorCodes.SERVICE_UNAVAILABLE,
                    )
                }
            }
        }

    fun uiClick(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_CLICK
            override val description =
                "Tap an interactive UI element by index label or target text."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson =
                """{"type":"object","properties":{"target":{"type":"string","description":"Target element text or index number"},"x":{"type":"number","description":"Optional exact X coordinate"},"y":{"type":"number","description":"Optional exact Y coordinate"}}}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val target = args?.string("target") ?: ""
                val x = args?.double("x")?.toFloat()
                val y = args?.double("y")?.toFloat()

                if (target.isBlank() && (x == null || y == null)) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing 'target' or ('x', 'y') coordinates.",
                        error = "missing_target",
                    )
                }

                val result = controller.tap(target = target, x = x, y = y)
                return ToolResult(
                    success = result.success,
                    observationText = result.toObservationText(),
                    error = result.errorCode,
                    structuredData = mapOf<String, Any>(
                        "action" to "click",
                        "target" to (result.target ?: ""),
                        "method" to (result.method ?: ""),
                        "state_changed" to result.stateChanged,
                    ),
                )
            }
        }

    fun uiType(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_TYPE
            override val description =
                "Type text into an editable input field."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson =
                """{"type":"object","properties":{"text":{"type":"string","description":"Text to type"},"target":{"type":"string","description":"Optional target field label or index"},"clear_first":{"type":"boolean","description":"Whether to clear before typing"},"submit":{"type":"boolean","description":"Whether to trigger IME submit (Enter) after typing"}},"required":["text"]}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val text = args?.string("text")
                if (text.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing required 'text' parameter.",
                        error = "missing_text",
                    )
                }

                val target = args.string("target")
                val clearFirst = args.boolean("clear_first") ?: false
                val submit = args.boolean("submit") ?: false

                val result = controller.type(
                    text = text,
                    target = target,
                    clearFirst = clearFirst,
                    submit = submit,
                )

                return ToolResult(
                    success = result.success,
                    observationText = result.toObservationText(),
                    error = result.errorCode,
                    structuredData = mapOf(
                        "action" to "type",
                        "text" to text,
                        "target" to (target ?: ""),
                        "state_changed" to result.stateChanged,
                    ),
                )
            }
        }

    fun uiSubmit(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_SUBMIT
            override val description =
                "Explicitly submit an input or focused field using IME Enter action."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson =
                """{"type":"object","properties":{"target":{"type":"string","description":"Optional target field label or index"}}}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val target = args?.string("target")

                val result = controller.submit(target = target)
                return ToolResult(
                    success = result.success,
                    observationText = result.toObservationText(),
                    error = result.errorCode,
                    structuredData = mapOf(
                        "action" to "submit",
                        "target" to (target ?: "focused_input"),
                        "state_changed" to result.stateChanged,
                    ),
                )
            }
        }

    fun uiScroll(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_SCROLL
            override val description =
                "Scroll active window in a given direction: down, up, left, or right."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson =
                """{"type":"object","properties":{"direction":{"type":"string","enum":["down","up","left","right"],"description":"Scroll direction"}},"required":["direction"]}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val direction = args?.string("direction") ?: "down"
                val result = controller.scroll(direction = direction)

                return ToolResult(
                    success = result.success,
                    observationText = result.toObservationText(),
                    error = result.errorCode,
                    structuredData = mapOf(
                        "action" to "scroll",
                        "direction" to direction,
                        "state_changed" to result.stateChanged,
                    ),
                )
            }
        }

    fun uiSwipe(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_SWIPE
            override val description =
                "Perform a swipe gesture between two points (start_x, start_y) -> (end_x, end_y)."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson =
                """{"type":"object","properties":{"start_x":{"type":"number"},"start_y":{"type":"number"},"end_x":{"type":"number"},"end_y":{"type":"number"},"duration_ms":{"type":"integer"}},"required":["start_x","start_y","end_x","end_y"]}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val startX = args?.double("start_x")?.toFloat()
                val startY = args?.double("start_y")?.toFloat()
                val endX = args?.double("end_x")?.toFloat()
                val endY = args?.double("end_y")?.toFloat()
                val durationMs = args?.int("duration_ms")?.toLong() ?: 320L

                if (startX == null || startY == null || endX == null || endY == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing coordinates for swipe gesture.",
                        error = "missing_coordinates",
                    )
                }

                val result = controller.swipe(startX, startY, endX, endY, durationMs = durationMs)
                return ToolResult(
                    success = result.success,
                    observationText = result.toObservationText(),
                    error = result.errorCode,
                    structuredData = mapOf(
                        "action" to "swipe",
                        "state_changed" to result.stateChanged,
                    ),
                )
            }
        }

    fun uiBack(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_BACK
            override val description = "Navigate back using global Android back action."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val result = controller.goBack()
                return ToolResult(
                    success = result.success,
                    observationText = result.toObservationText(),
                    error = result.errorCode,
                    structuredData = mapOf(
                        "action" to "back",
                        "state_changed" to result.stateChanged,
                    ),
                )
            }
        }

    fun uiVerify(controller: AutomationController): Tool =
        object : Tool {
            override val name = UI_VERIFY
            override val description =
                "Verify that expected text, button, or package name is visible on screen."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson =
                """{"type":"object","properties":{"expected":{"type":"string","description":"Expected text or label on screen"},"package_name":{"type":"string","description":"Optional expected package name"},"timeout_ms":{"type":"integer","description":"Timeout in milliseconds"}},"required":["expected"]}"""

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val expected = args?.string("expected")
                val expectedPkg = args?.string("package_name") ?: args?.string("packageName")
                if (expected.isNullOrBlank() && expectedPkg.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing 'expected' parameter for ui_verify.",
                        error = "missing_expected",
                    )
                }

                val timeoutMs = args?.int("timeout_ms")?.toLong() ?: 2000L
                val result = controller.verify(expected = expected, expectedPackage = expectedPkg, timeoutMs = timeoutMs)
                return ToolResult(
                    success = result.success,
                    observationText = result.toObservationText(),
                    error = result.errorCode,
                    structuredData = mapOf(
                        "action" to "verify",
                        "verified" to result.success,
                        "expected" to (expected ?: expectedPkg.orEmpty()),
                        "package_name" to (result.packageName ?: ""),
                    ),
                )
            }
        }
}
