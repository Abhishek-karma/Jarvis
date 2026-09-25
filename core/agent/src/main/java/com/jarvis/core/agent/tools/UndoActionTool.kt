package com.jarvis.core.agent.tools

import com.jarvis.core.agent.ReversibleActionExecutor
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.database.repository.ReversibleActionRepository
import kotlinx.coroutines.flow.first

object UndoActionTool {
    const val UNDO_ACTION = "undo_action"

    private const val SCHEMA = """{
  "type": "object",
  "properties": {
    "action_id": {"type": "string", "description": "Optional ID of the specific action to revert. If omitted, reverts the most recent reversible action."}
  }
}"""

    fun create(
        executor: ReversibleActionExecutor,
        actionRepository: ReversibleActionRepository,
    ): Tool = object : Tool {
        override val name = UNDO_ACTION
        override val description = "Reverts or undoes a recent reversible action (e.g., calendar event creation, memory changes, or preference updates)."
        override val parametersSchemaJson = SCHEMA
        override val tier = PermissionTier.REVERSIBLE_WRITE

        override suspend fun execute(argsJson: String): ToolResult {
            val args = Args.parse(argsJson)
            var actionId = args?.string("action_id")

            if (actionId.isNullOrBlank()) {
                val recent = actionRepository.observeRecent(1).first()
                val target = recent.firstOrNull()
                if (target == null) {
                    return ToolResult(
                        success = false,
                        observationText = "No recent reversible actions found to undo.",
                        error = "No actions available",
                    )
                }
                actionId = target.id
            }

            return executor.revertAction(actionId).fold(
                onSuccess = { msg ->
                    ToolResult(
                        success = true,
                        observationText = msg,
                        structuredData = mapOf("actionId" to actionId, "reverted" to true),
                    )
                },
                onFailure = { err ->
                    ToolResult(
                        success = false,
                        observationText = "Failed to revert action: ${err.message}",
                        error = err.message,
                    )
                },
            )
        }
    }
}
