package com.jarvis.core.agent.tools

import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.agent.automation.PhoneAgent
import com.jarvis.core.agent.execution.ErrorCode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level tool exposing autonomous phone UI interaction to Jarvis AgentRunner.
 */
@Singleton
class PhoneAgentTool @Inject constructor(
    private val phoneAgent: PhoneAgent,
    private val confirmationGate: ConfirmationGate? = null,
) : Tool {

    companion object {
        const val NAME = "phone_agent"
    }

    override val name: String = NAME

    override val description: String =
        "Autonomously navigate apps and device screens using accessibility to complete a task (e.g. search in YouTube/Instagram, go to Wi-Fi in Settings, type a message in WhatsApp)."

    override val parametersSchemaJson: String = """
        {
          "type": "object",
          "properties": {
            "goal": {
              "type": "string",
              "description": "Specific automation goal to perform on the device screen (e.g. 'search Android on YouTube', 'go to Wi-Fi in Settings', 'type a message to Rahul in WhatsApp')"
            },
            "app_name": {
              "type": "string",
              "description": "Optional target application name (e.g. 'YouTube', 'Instagram', 'Settings', 'WhatsApp')"
            }
          },
          "required": ["goal"]
        }
    """.trimIndent()

    override val tier: PermissionTier = PermissionTier.REVERSIBLE_WRITE

    override suspend fun execute(argsJson: String): ToolResult {
        val parsed = Args.parse(argsJson)
        val goal = parsed?.string("goal") ?: return ToolResult(
            success = false,
            observationText = "Missing 'goal' parameter.",
            error = "MISSING_ARGUMENT",
            errorCode = ErrorCode.INVALID_TOOL_ARGUMENTS,
        )
        val appName = parsed.string("app_name")

        val effectiveGate = confirmationGate
            ?: kotlinx.coroutines.currentCoroutineContext()[com.jarvis.core.agent.ConfirmationGateElement]?.gate

        val result = phoneAgent.execute(
            goal = goal,
            targetApp = appName,
            confirmationGate = effectiveGate,
        )

        return if (result.isSuccess) {
            ToolResult(
                success = true,
                observationText = result.message,
                structuredData = mapOf(
                    "status" to result.status.name,
                    "completedSteps" to result.completedSteps,
                ),
            )
        } else {
            ToolResult(
                success = false,
                observationText = result.message,
                error = result.errorCode?.name ?: "AUTOMATION_FAILED",
                errorCode = result.errorCode ?: ErrorCode.ACTION_FAILED,
                structuredData = mapOf(
                    "status" to result.status.name,
                    "completedSteps" to result.completedSteps,
                ),
            )
        }
    }
}
