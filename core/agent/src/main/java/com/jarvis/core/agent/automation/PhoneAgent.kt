package com.jarvis.core.agent.automation

import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.automation.engine.PhoneAgentEngine
import com.jarvis.core.agent.automation.engine.PhoneAutomationDriver
import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.agent.execution.ExecutionStatus
import com.jarvis.core.network.LlmProvider
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a PhoneAgent automation execution.
 */
data class PhoneAgentResult(
    val status: ExecutionStatus,
    val message: String,
    val errorCode: ErrorCode? = null,
    val completedSteps: List<String> = emptyList(),
) {
    val isSuccess: Boolean
        get() = status == ExecutionStatus.SUCCESS || status == ExecutionStatus.PARTIAL_SUCCESS

    companion object {
        fun success(message: String, completedSteps: List<String> = emptyList()) =
            PhoneAgentResult(
                status = ExecutionStatus.SUCCESS,
                message = message,
                completedSteps = completedSteps,
            )

        fun failure(message: String, errorCode: ErrorCode, completedSteps: List<String> = emptyList()) =
            PhoneAgentResult(
                status = ExecutionStatus.FAILED,
                message = message,
                errorCode = errorCode,
                completedSteps = completedSteps,
            )

        fun cancelled(message: String = "Action cancelled by user", completedSteps: List<String> = emptyList()) =
            PhoneAgentResult(
                status = ExecutionStatus.CANCELLED,
                message = message,
                errorCode = ErrorCode.USER_CANCELLED,
                completedSteps = completedSteps,
            )
    }
}

/**
 * High-level PhoneAgent interface.
 * Provides a single, clean entry point for phone UI automation.
 */
interface PhoneAgent {
    suspend fun execute(
        goal: String,
        targetApp: String? = null,
        confirmationGate: ConfirmationGate? = null,
        onProgress: (suspend (String) -> Unit)? = null,
    ): PhoneAgentResult
}

/**
 * Thin PhoneAgent adapter connecting Jarvis AgentRunner to PhoneAgentEngine.
 *
 * Responsibilities:
 * - Validates service readiness.
 * - Handles optional target app pre-flight launch.
 * - Delegates perception, action loop, and verification to PhoneAgentEngine.
 */
@Singleton
class DefaultPhoneAgent @Inject constructor(
    private val serviceProvider: () -> JarvisAccessibilityService? = { JarvisAccessibilityService.instance },
    private val launchApp: suspend (String) -> Result<Unit>,
    private val llmProvider: (suspend () -> LlmProvider?)? = null,
    private val modelIdProvider: (suspend () -> String)? = null,
    private val driver: PhoneAutomationDriver = AutomationController(serviceProvider),
    private val confirmationGate: ConfirmationGate? = null,
    private val toolPolicy: com.jarvis.core.agent.ToolPolicy = com.jarvis.core.agent.DefaultToolPolicy(),
    private val phoneActionModel: com.jarvis.core.agent.automation.engine.PhoneActionModel = com.jarvis.core.agent.automation.engine.UnavailablePhoneActionModel(),
    private val requirePhoneActionModel: Boolean = false,
    private val maxSteps: Int = PhoneAgentEngine.DEFAULT_MAX_STEPS,
    private val decisionTimeoutMs: Long = PhoneAgentEngine.DEFAULT_DECISION_TIMEOUT_MS,
    private val overallTimeoutMs: Long = PhoneAgentEngine.DEFAULT_OVERALL_TIMEOUT_MS,
    private val engine: PhoneAgentEngine = PhoneAgentEngine(
        driver = driver,
        phoneActionModel = phoneActionModel,
        requirePhoneActionModel = requirePhoneActionModel,
        llmProvider = llmProvider,
        modelIdProvider = modelIdProvider,
        toolPolicy = toolPolicy,
        confirmationGate = confirmationGate,
        maxSteps = maxSteps,
        decisionTimeoutMs = decisionTimeoutMs,
        overallTimeoutMs = overallTimeoutMs,
    ),
) : PhoneAgent {

    override suspend fun execute(
        goal: String,
        targetApp: String?,
        confirmationGate: ConfirmationGate?,
        onProgress: (suspend (String) -> Unit)?,
    ): PhoneAgentResult {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isBlank()) {
            return PhoneAgentResult.failure("Goal cannot be empty.", ErrorCode.INVALID_TOOL_ARGUMENTS)
        }

        val service = serviceProvider()
        if (service == null) {
            return PhoneAgentResult.failure(
                "Jarvis needs Accessibility access to interact with apps.",
                ErrorCode.ACCESSIBILITY_UNAVAILABLE,
            )
        }

        // Optional pre-flight app launch if a specific target app was explicitly designated
        if (!targetApp.isNullOrBlank()) {
            val currentPkg = service.lastObservedPackage ?: service.rootInActiveWindow?.packageName?.toString()
            val targetLower = targetApp.lowercase(Locale.US)
            val alreadyForeground = currentPkg != null && currentPkg.contains(targetLower, ignoreCase = true)

            if (!alreadyForeground) {
                onProgress?.invoke("Opening $targetApp…")
                val launched = launchApp(targetApp)

                if (launched.isFailure) {
                    val exMsg = launched.exceptionOrNull()?.message.orEmpty()
                    val notInstalled = exMsg.contains("not found", ignoreCase = true) ||
                        exMsg.contains("not installed", ignoreCase = true)
                    return PhoneAgentResult.failure(
                        message = if (notInstalled) "$targetApp isn't installed." else "Could not open $targetApp.",
                        errorCode = if (notInstalled) ErrorCode.APP_NOT_INSTALLED else ErrorCode.ACTION_FAILED,
                    )
                }

                service.waitForUiChange(service.uiRevision, timeoutMs = 1500L)
                delay(400L)
            }
        }

        val effectiveGate = confirmationGate
            ?: kotlinx.coroutines.currentCoroutineContext()[com.jarvis.core.agent.ConfirmationGateElement]?.gate

        return engine.execute(
            goal = trimmedGoal,
            targetApp = targetApp,
            confirmationGate = effectiveGate,
            onProgress = onProgress,
        )
    }
}
