package com.jarvis.core.agent

import com.jarvis.core.database.repository.MemoryRepository
import com.jarvis.core.database.repository.ReversibleActionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReversibleActionExecutor(
    private val actionRepository: ReversibleActionRepository,
    private val memoryRepository: MemoryRepository,
) {
    /**
     * Handler invoked when an external system (e.g. Calendar provider) needs to revert a target.
     * Takes (actionType, targetId, inverseActionJson) -> Result<Boolean>.
     */
    private var externalRevertHandler: (suspend (actionType: String, target: String, inverseJson: String) -> Result<Boolean>)? = null

    fun setExternalRevertHandler(handler: suspend (actionType: String, target: String, inverseJson: String) -> Result<Boolean>) {
        externalRevertHandler = handler
    }

    suspend fun revertAction(actionId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val action = actionRepository.get(actionId)
                ?: error("Action with ID $actionId not found")
            if (action.isReverted) {
                return@runCatching "Action [${action.actionType}] on target ${action.target} was already reverted."
            }

            when (action.actionType) {
                "memory" -> {
                    memoryRepository.delete(action.target)
                }
                "calendar_event" -> {
                    val handler = externalRevertHandler
                        ?: error("No handler registered for calendar revert operations")
                    handler("calendar_event", action.target, action.inverseActionJson).getOrThrow()
                }
                else -> {
                    val handler = externalRevertHandler
                    if (handler != null) {
                        handler(action.actionType, action.target, action.inverseActionJson).getOrThrow()
                    } else {
                        error("Unsupported reversible action type: ${action.actionType}")
                    }
                }
            }

            actionRepository.markReverted(actionId)
            "Successfully reverted ${action.actionType} action on ${action.target}."
        }
    }
}
