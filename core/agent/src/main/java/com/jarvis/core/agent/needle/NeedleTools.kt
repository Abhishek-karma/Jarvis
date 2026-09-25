package com.jarvis.core.agent.needle

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolExecutor
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.agent.tools.Args
import com.jarvis.core.common.PermissionTier

/**
 * Needle on-device capability dispatcher tool for LLM agent.
 *
 * Enables the LLM brain to dynamically delegate fast on-device actions to Needle when desired.
 * Needle resolves the command to a native tool, executes it, and returns the observation
 * for LLM verification and synthesis.
 */
object NeedleTools {
    const val NEEDLE_ACTION = "needle_action"

    fun needleAction(
        needleRouter: NeedleRouter,
        executeTool: suspend (toolName: String, argsJson: String) -> ToolResult,
    ): Tool = object : Tool {
        override val name: String = NEEDLE_ACTION
        override val description: String =
            "Execute a quick on-device natural language action using the high-speed Needle router " +
                "(e.g., 'turn on flashlight', 'open spotify', 'play jazz', 'mute media volume', 'check battery level'). " +
                "Needle routes to the matching native tool and returns the execution observation for LLM verification."
        override val tier: PermissionTier = PermissionTier.REVERSIBLE_WRITE
        override val parametersSchemaJson: String = SCHEMA

        override suspend fun execute(argsJson: String): ToolResult {
            val args = Args.parse(argsJson)
            val action = args?.string("action")?.trim().orEmpty()
            if (action.isBlank()) {
                return ToolResult(
                    success = false,
                    observationText = "Missing required 'action' parameter for needle_action.",
                    error = "missing_action",
                )
            }

            val decision = needleRouter.route(action)
            return when (decision) {
                is RoutingDecision.Direct -> {
                    if (decision.toolName == NEEDLE_ACTION) {
                        return ToolResult(
                            success = false,
                            observationText = "Recursive needle invocation blocked.",
                            error = "recursive_needle_call",
                        )
                    }
                    val outcome = executeTool(decision.toolName, decision.argsJson)
                    ToolResult(
                        success = outcome.success,
                        observationText = outcome.observationText.ifBlank { "Executed ${decision.toolName} successfully." },
                        structuredData = mapOf(
                            "routed_tool" to decision.toolName,
                            "user_action" to decision.userFacingAction,
                            "success" to outcome.success,
                        ),
                        error = if (!outcome.success) (outcome.error ?: outcome.observationText) else null,
                        errorCode = outcome.errorCode,
                    )
                }
                is RoutingDecision.Escalate -> {
                    ToolResult(
                        success = false,
                        observationText = "Needle could not directly route '$action' (${decision.reason}). Please invoke a specific tool directly (e.g. launch_app, play_media, phone_agent, etc.).",
                        error = "needle_escalated_${decision.reason.name}",
                    )
                }
            }
        }
    }

    fun needleAction(
        needleRouter: NeedleRouter,
        toolExecutor: ToolExecutor,
    ): Tool = needleAction(needleRouter) { name, args ->
        val outcome = toolExecutor.execute(name, args)
        ToolResult(
            success = outcome.success,
            observationText = outcome.observationText,
            error = outcome.errorCode?.name ?: if (!outcome.success) (outcome.rejectionReason ?: outcome.observationText) else null,
            errorCode = outcome.errorCode,
        )
    }

    private const val SCHEMA = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "description": "Natural language on-device action or query to execute via Needle (e.g. 'open spotify', 'play jazz', 'turn off flashlight', 'increase volume')"
            }
        },
        "required": ["action"]
    }"""
}
