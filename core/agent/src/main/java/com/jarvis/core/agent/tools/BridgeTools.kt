package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.agent.bridge.BridgeCoordinator
import com.jarvis.core.agent.bridge.OpResult
import com.jarvis.core.agent.bridge.TypedOp
import com.jarvis.core.common.PermissionTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tools exposing typed privileged device operations and policy-governed shell commands.
 */
object BridgeTools {

    fun all(bridgeCoordinator: BridgeCoordinator): List<Tool> = listOf(
        GrantPermissionTool(bridgeCoordinator),
        RevokePermissionTool(bridgeCoordinator),
        ForceStopAppTool(bridgeCoordinator),
        SetAppStateTool(bridgeCoordinator),
        SetSystemSettingTool(bridgeCoordinator),
        ExecuteShellCommandTool(bridgeCoordinator),
    )

    class GrantPermissionTool(
        private val coordinator: BridgeCoordinator,
    ) : Tool {
        override val name: String = "grant_permission"
        override val description: String =
            "Grants a runtime permission to an application silently using privileged device bridge. Requires confirmation."
        override val tier: PermissionTier = PermissionTier.SENSITIVE
        override val parametersSchemaJson: String = """
            {
                "type": "object",
                "properties": {
                    "package_name": { "type": "string", "description": "Target Android package name (e.g. com.example.app)" },
                    "permission": { "type": "string", "description": "Permission to grant (e.g. android.permission.CAMERA)" }
                },
                "required": ["package_name", "permission"]
            }
        """.trimIndent()

        override suspend fun execute(argsJson: String): ToolResult {
            val json = Json.parseToJsonElement(argsJson).jsonObject
            val pkg = json["package_name"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing package_name")
            val perm = json["permission"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing permission")

            val result = coordinator.execute(TypedOp.GrantPermission(pkg, perm))
            return mapOpResultToToolResult(result)
        }
    }

    class RevokePermissionTool(
        private val coordinator: BridgeCoordinator,
    ) : Tool {
        override val name: String = "revoke_permission"
        override val description: String =
            "Revokes a permission from an application using privileged bridge. Requires confirmation."
        override val tier: PermissionTier = PermissionTier.SENSITIVE
        override val parametersSchemaJson: String = """
            {
                "type": "object",
                "properties": {
                    "package_name": { "type": "string", "description": "Target Android package name" },
                    "permission": { "type": "string", "description": "Permission to revoke" }
                },
                "required": ["package_name", "permission"]
            }
        """.trimIndent()

        override suspend fun execute(argsJson: String): ToolResult {
            val json = Json.parseToJsonElement(argsJson).jsonObject
            val pkg = json["package_name"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing package_name")
            val perm = json["permission"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing permission")

            val result = coordinator.execute(TypedOp.RevokePermission(pkg, perm))
            return mapOpResultToToolResult(result)
        }
    }

    class ForceStopAppTool(
        private val coordinator: BridgeCoordinator,
    ) : Tool {
        override val name: String = "force_stop_app"
        override val description: String =
            "Terminates all running processes of an application package. Requires confirmation."
        override val tier: PermissionTier = PermissionTier.SENSITIVE
        override val parametersSchemaJson: String = """
            {
                "type": "object",
                "properties": {
                    "package_name": { "type": "string", "description": "Target Android package name" }
                },
                "required": ["package_name"]
            }
        """.trimIndent()

        override suspend fun execute(argsJson: String): ToolResult {
            val json = Json.parseToJsonElement(argsJson).jsonObject
            val pkg = json["package_name"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing package_name")

            val result = coordinator.execute(TypedOp.ForceStop(pkg))
            return mapOpResultToToolResult(result)
        }
    }

    class SetAppStateTool(
        private val coordinator: BridgeCoordinator,
    ) : Tool {
        override val name: String = "set_app_state"
        override val description: String =
            "Enables or disables an application package on device. Requires confirmation."
        override val tier: PermissionTier = PermissionTier.SENSITIVE
        override val parametersSchemaJson: String = """
            {
                "type": "object",
                "properties": {
                    "package_name": { "type": "string", "description": "Target Android package name" },
                    "enabled": { "type": "boolean", "description": "True to enable, false to disable" }
                },
                "required": ["package_name", "enabled"]
            }
        """.trimIndent()

        override suspend fun execute(argsJson: String): ToolResult {
            val json = Json.parseToJsonElement(argsJson).jsonObject
            val pkg = json["package_name"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing package_name")
            val enabled = json["enabled"]?.jsonPrimitive?.booleanOrNull ?: return ToolResult(false, "Missing enabled flag")

            val result = coordinator.execute(TypedOp.SetAppEnabled(pkg, enabled))
            return mapOpResultToToolResult(result)
        }
    }

    class SetSystemSettingTool(
        private val coordinator: BridgeCoordinator,
    ) : Tool {
        override val name: String = "set_system_setting"
        override val description: String =
            "Modifies a global or secure Android system setting (e.g. Wi-Fi / Bluetooth / DND states). Requires confirmation."
        override val tier: PermissionTier = PermissionTier.SENSITIVE
        override val parametersSchemaJson: String = """
            {
                "type": "object",
                "properties": {
                    "table": { "type": "string", "enum": ["global", "secure"], "description": "Settings table: global or secure" },
                    "key": { "type": "string", "description": "Setting key name (e.g. wifi_on, bluetooth_on)" },
                    "value": { "type": "string", "description": "Value to set" }
                },
                "required": ["table", "key", "value"]
            }
        """.trimIndent()

        override suspend fun execute(argsJson: String): ToolResult {
            val json = Json.parseToJsonElement(argsJson).jsonObject
            val table = json["table"]?.jsonPrimitive?.content ?: "global"
            val key = json["key"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing setting key")
            val value = json["value"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing setting value")

            val op = if (table.equals("secure", ignoreCase = true)) {
                TypedOp.SetSecureSetting(key, value)
            } else {
                TypedOp.SetGlobalSetting(key, value)
            }

            val result = coordinator.execute(op)
            return mapOpResultToToolResult(result)
        }
    }

    class ExecuteShellCommandTool(
        private val coordinator: BridgeCoordinator,
    ) : Tool {
        override val name: String = "execute_shell_command"
        override val description: String =
            "Executes a policy-checked shell command via privileged bridge. Hard-blocked destructive commands are rejected. Requires user confirmation."
        override val tier: PermissionTier = PermissionTier.SENSITIVE
        override val parametersSchemaJson: String = """
            {
                "type": "object",
                "properties": {
                    "command": { "type": "string", "description": "Shell command to execute" }
                },
                "required": ["command"]
            }
        """.trimIndent()

        override suspend fun execute(argsJson: String): ToolResult {
            val json = Json.parseToJsonElement(argsJson).jsonObject
            val cmd = json["command"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing command")

            val result = coordinator.execute(TypedOp.ShellCommand(cmd))
            return mapOpResultToToolResult(result)
        }
    }

    private fun mapOpResultToToolResult(result: OpResult): ToolResult {
        return when (result) {
            is OpResult.Success -> ToolResult(
                success = true,
                observationText = "[Tier: ${result.tierUsed.name}] ${result.output}",
                structuredData = result.data,
            )
            is OpResult.Unavailable -> ToolResult(
                success = false,
                observationText = "Action unavailable: ${result.reason}\nSetup hint: ${result.setupHint}",
                error = result.reason,
            )
            is OpResult.Blocked -> ToolResult(
                success = false,
                observationText = "Action blocked by safety policy: ${result.reason}",
                error = result.reason,
            )
            is OpResult.Failed -> ToolResult(
                success = false,
                observationText = "Execution failed: ${result.error}",
                error = result.error,
            )
        }
    }
}
