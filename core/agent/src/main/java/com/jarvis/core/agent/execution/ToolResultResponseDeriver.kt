package com.jarvis.core.agent.execution

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Record of an executed tool used to synthesize evidence-based user responses.
 */
data class ToolExecutionRecord(
    val toolName: String,
    val success: Boolean,
    val observationText: String,
    val rawArgs: String = "",
)

/**
 * Derives truthful, grounded, concise user responses from tool execution outcomes.
 *
 * Eliminates generic, unevidenced fallback responses such as:
 * - "Task completed."
 * - "Done."
 * - "Completed <tool_name>."
 * - "Routine executed successfully."
 *
 * Grounding rules:
 * 1. If an action succeeded: describe what happened based on tool observation.
 * 2. If an action failed: explain the failure.
 * 3. If a result is uncertain: explicitly state that the outcome is uncertain and could not be verified.
 * 4. If the model produces an empty or generic response after tool execution: derive a concise response
 *    directly from the actual tool result.
 * 5. Never invent success. Never claim an action happened without concrete evidence.
 */
object ToolResultResponseDeriver {

    private val GENERIC_FALLBACK_PHRASES = setOf(
        "task completed",
        "task completed.",
        "task completed successfully",
        "task completed successfully.",
        "done",
        "done.",
        "done!",
        "finished",
        "finished.",
        "success",
        "success.",
        "action completed",
        "action completed.",
        "routine executed successfully",
        "routine executed successfully.",
        "routine completed",
        "routine completed.",
        "operation completed successfully",
        "operation completed successfully.",
        "completed",
        "completed.",
        "ok",
        "ok.",
    )

    private val COMPLETED_TOOL_REGEX = Regex("""^completed\s+[a-z0-9_.-]+[.! ]*$""", RegexOption.IGNORE_CASE)

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Determines whether the given text is a generic, ungrounded placeholder or fallback.
     */
    fun isGenericFallback(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val clean = text.trim()
            .removePrefix("✓")
            .trim()
            .lowercase(Locale.ROOT)

        if (clean.isBlank()) return true
        if (clean in GENERIC_FALLBACK_PHRASES) return true
        if (COMPLETED_TOOL_REGEX.matches(clean)) return true
        if (clean.startsWith("completed ") && clean.length <= 40 && !clean.contains("\n")) return true

        return false
    }

    /**
     * Checks if the observation or error code indicates an uncertain outcome.
     */
    fun isUncertain(observationText: String?, errorCode: ErrorCode?): Boolean {
        if (errorCode == ErrorCode.OPERATION_UNCERTAIN) return true
        if (observationText.isNullOrBlank()) return false
        val lower = observationText.lowercase(Locale.ROOT)
        return lower.contains("uncertain") ||
            lower.contains("undetermined") ||
            lower.contains("unknown state") ||
            lower.contains("could not be safely determined") ||
            lower.contains("could not verify") ||
            lower.contains("verification failed") ||
            lower.contains("unverified")
    }

    /**
     * Checks if the observation is a low-information token that lacks descriptive substance.
     */
    fun isLowInformationToken(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val clean = text.trim()
            .removePrefix("✓")
            .removeSuffix(".")
            .removeSuffix("!")
            .trim()
            .lowercase(Locale.ROOT)

        return clean.isEmpty() ||
            clean == "ok" ||
            clean == "true" ||
            clean == "success" ||
            clean == "{}" ||
            clean == "[]" ||
            clean == "done" ||
            clean == "finished" ||
            clean == "null" ||
            clean == "1"
    }

    /**
     * Sanitizes any internal architecture concepts to ensure they are never exposed to users
     * unless explicitly asked.
     * Forbidden terms: AgentRunner, ToolExecutor, TaskEngine, OperationRepository, idempotency, execution state.
     */
    fun sanitizeArchitectureConcepts(text: String): String {
        if (text.isBlank()) return text
        var sanitized = text
        sanitized = sanitized.replace(Regex("""\bAgentRunner\b""", RegexOption.IGNORE_CASE), "the assistant")
        sanitized = sanitized.replace(Regex("""\bToolExecutor\b""", RegexOption.IGNORE_CASE), "the system")
        sanitized = sanitized.replace(Regex("""\bTaskEngine\b""", RegexOption.IGNORE_CASE), "the task coordinator")
        sanitized = sanitized.replace(Regex("""\bOperationRepository\b""", RegexOption.IGNORE_CASE), "system records")
        sanitized = sanitized.replace(Regex("""\bidempotency(?:[-\w]*)\b""", RegexOption.IGNORE_CASE), "duplicate check")
        sanitized = sanitized.replace(Regex("""\bexecution\s+state\b""", RegexOption.IGNORE_CASE), "progress")
        return sanitized
    }

    private fun humanizeToolAction(toolName: String): String {
        val lower = toolName.lowercase(Locale.ROOT)
        return when (lower) {
            "play_media" -> "play music"
            "media_control" -> "control playback"
            "list_events" -> "access your calendar"
            "create_event", "create_calendar_event" -> "create the calendar event"
            "set_reminder", "create_task" -> "set the reminder"
            "battery_level" -> "check the battery level"
            "storage_free" -> "check storage space"
            "network_status" -> "check the network status"
            "set_flashlight" -> "change the flashlight state"
            "set_alarm" -> "set the alarm"
            "set_timer" -> "set the timer"
            "adjust_volume" -> "adjust the volume"
            "send_sms", "send_message" -> "send the message"
            "place_call" -> "place the call"
            "launch_app" -> "open the app"
            "open_settings" -> "open settings"
            "phone_agent" -> "complete the task on screen"
            "needle_action" -> "execute the action"
            "transfer_funds" -> "transfer funds"
            else -> lower.replace('_', ' ')
        }
    }

    /**
     * Derives a concise, truthful response from a single tool outcome adhering to the contract:
     * Tool execution result -> Agent reasoning -> Useful user-facing answer.
     */
    fun deriveConciseResponse(
        toolName: String,
        observationText: String,
        success: Boolean,
        errorCode: ErrorCode? = null,
        rawArgs: String = "",
    ): String {
        val trimmedObs = observationText.trim()

        // 1. Unknown / Uncertain external result
        if (isUncertain(trimmedObs, errorCode)) {
            return "I started the operation, but I can't confirm whether it completed."
        }

        // 2. Failure outcome: "I couldn't ... because ..."
        if (!success || errorCode != null) {
            val errorDetail = if (trimmedObs.isNotBlank() && !isLowInformationToken(trimmedObs)) {
                trimmedObs
            } else if (errorCode != null) {
                ExecutionErrorMapper.map(errorCode, trimmedObs).message
            } else {
                "the operation failed"
            }
            val cleanDetail = sanitizeArchitectureConcepts(errorDetail).trim().removeSuffix(".")
            val userFriendlyAction = humanizeToolAction(toolName)
            return "I couldn't $userFriendlyAction because $cleanDetail."
        }

        // 3. Success outcome: Say what happened based on real evidence

        // 3a. Battery Level: "Your battery is at 68%."
        if (toolName.equals("battery_level", ignoreCase = true) ||
            trimmedObs.contains(Regex("""(?i)battery\s+(?:at|is)\s+\d+%"""))
        ) {
            val percentMatch = Regex("""(\d+%)""").find(trimmedObs)
            if (percentMatch != null) {
                return "Your battery is at ${percentMatch.groupValues[1]}."
            }
            if (trimmedObs.isNotBlank() && !isLowInformationToken(trimmedObs)) {
                return sanitizeArchitectureConcepts(trimmedObs)
            }
        }

        // 3b. Music: "Started playing ..."
        if (toolName.equals("play_media", ignoreCase = true)) {
            if (trimmedObs.startsWith("Playing ", ignoreCase = true)) {
                return "Started playing " + trimmedObs.substring(8)
            }
            if (trimmedObs.startsWith("Started playing", ignoreCase = true)) {
                return sanitizeArchitectureConcepts(trimmedObs)
            }
            if (trimmedObs.isNotBlank() && !isLowInformationToken(trimmedObs)) {
                return sanitizeArchitectureConcepts(trimmedObs)
            }
            return "Started playing requested media."
        }

        // 3c. Calendar Events List: "Your calendar today has 2 events: ..."
        if (toolName.equals("list_events", ignoreCase = true)) {
            if (trimmedObs.contains("No events between", ignoreCase = true) ||
                trimmedObs.contains("No events found", ignoreCase = true) ||
                trimmedObs.contains("no entries", ignoreCase = true)
            ) {
                return "Your calendar has no events scheduled."
            }
            val eventMatch = Regex("""(?s)(\d+)\s+event\(s\):\s*\n(.*)""").find(trimmedObs)
            if (eventMatch != null) {
                val count = eventMatch.groupValues[1]
                val eventsList = eventMatch.groupValues[2].trim()
                return "Your calendar today has $count event(s):\n$eventsList"
            }
        }

        // 3d. Structured JSON observation
        val parsedJson = parseJsonObservation(trimmedObs)
        if (parsedJson != null) {
            return sanitizeArchitectureConcepts(parsedJson)
        }

        // 3e. Informative plain text observation
        if (!isLowInformationToken(trimmedObs)) {
            return sanitizeArchitectureConcepts(trimmedObs)
        }

        // 3f. Minimal / low-information observation (e.g. "ok")
        return sanitizeArchitectureConcepts(describeActionFactually(toolName, rawArgs, trimmedObs))
    }

    /**
     * Parses and formats JSON observations (e.g. calendar events, status objects).
     */
    private fun parseJsonObservation(obs: String): String? {
        val trimmed = obs.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null

        return try {
            val element = jsonParser.parseToJsonElement(trimmed)
            when (element) {
                is JsonArray -> formatJsonArray(element)
                is JsonObject -> formatJsonObject(element)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatJsonArray(array: JsonArray): String {
        if (array.isEmpty()) {
            return "Your calendar has no events scheduled."
        }
        val items = mutableListOf<String>()
        for (i in array.indices) {
            val element = array[i]
            if (element is JsonObject) {
                val title = element["title"]?.jsonPrimitive?.contentOrNull
                    ?: element["name"]?.jsonPrimitive?.contentOrNull
                    ?: element["summary"]?.jsonPrimitive?.contentOrNull
                    ?: element["label"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val time = element["time"]?.jsonPrimitive?.contentOrNull
                    ?: element["start"]?.jsonPrimitive?.contentOrNull
                    ?: element["startUtcMillis"]?.jsonPrimitive?.contentOrNull
                    ?: element["date"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val location = element["location"]?.jsonPrimitive?.contentOrNull ?: ""
                val desc = element["description"]?.jsonPrimitive?.contentOrNull ?: ""

                val entry = buildString {
                    if (title.isNotBlank()) append(title) else append("Event ${i + 1}")
                    if (time.isNotBlank()) append(" at $time")
                    if (location.isNotBlank()) append(" ($location)")
                    if (desc.isNotBlank()) append(" - $desc")
                }
                items.add(entry)
            } else {
                items.add(element.toString().removeSurrounding("\""))
            }
        }

        return buildString {
            appendLine("Your calendar today has ${array.size} event(s):")
            items.forEach { appendLine("• $it") }
        }.trim()
    }

    private fun formatJsonObject(obj: JsonObject): String {
        // Direct messages / descriptions
        val msg = obj["message"]?.jsonPrimitive?.contentOrNull
            ?: obj["summary"]?.jsonPrimitive?.contentOrNull
            ?: obj["description"]?.jsonPrimitive?.contentOrNull
            ?: obj["text"]?.jsonPrimitive?.contentOrNull
        if (!msg.isNullOrBlank()) return msg

        // Check for nested arrays
        val events = obj["events"] ?: obj["items"] ?: obj["results"]
        if (events is JsonArray) {
            return formatJsonArray(events)
        }

        // Direct result values
        if (obj.containsKey("result")) {
            val res = obj["result"]?.jsonPrimitive?.contentOrNull ?: obj["result"]?.toString()
            return "Result: $res"
        }

        // Generic key-value summary
        if (obj.isEmpty()) return "Operation returned an empty result."

        return obj.entries.joinToString(", ") { (key, value) ->
            val v = if (value is JsonObject || value is JsonArray) {
                value.toString()
            } else {
                value.jsonPrimitive.contentOrNull ?: value.toString()
            }
            "$key: $v"
        }
    }

    /**
     * Generates a truthful summary for low-information observations without fabricating claims.
     */
    private fun describeActionFactually(toolName: String, rawArgs: String, observation: String): String {
        val lower = toolName.lowercase(Locale.ROOT)
        return when (lower) {
            "set_flashlight", "flashlight", "torch" -> {
                if (rawArgs.contains("\"enabled\":false") || rawArgs.contains("\"enabled\": false") ||
                    rawArgs.contains("false") || observation.contains("off")
                ) {
                    "Flashlight turned off."
                } else {
                    "Flashlight turned on."
                }
            }
            "set_volume", "adjust_volume" -> "Audio volume adjusted."
            "set_alarm", "create_alarm" -> "Alarm set."
            "set_timer", "start_timer" -> "Timer started."
            "create_task", "add_task", "set_reminder" -> "Reminder created."
            "create_calendar_event" -> "Calendar event scheduled."
            "send_sms", "send_message", "send_app_message" -> "Message sent."
            "place_call", "call" -> "Call started."
            "launch_app", "open_app" -> "App opened."
            "open_settings" -> "Settings opened."
            "play_media", "media_control" -> "Started playing requested media."
            "calculator" -> "Calculation completed."
            "current_time", "get_current_datetime" -> {
                if (observation.isNotBlank() && !isLowInformationToken(observation)) {
                    observation
                } else {
                    "Action '$toolName' completed with result: ${observation.ifBlank { "ok" }}."
                }
            }
            else -> "Action '$toolName' completed with result: ${observation.ifBlank { "ok" }}."
        }
    }

    /**
     * Derives a truthful response from the execution history and failure state.
     */
    fun deriveFromHistory(
        executionHistory: List<ToolExecutionRecord>,
        lastToolFailed: Pair<String, ErrorCode>?,
    ): String {
        if (executionHistory.isEmpty()) {
            return "No action was performed."
        }

        if (lastToolFailed != null) {
            val (failedTool, errCode) = lastToolFailed
            val failedExec = executionHistory.lastOrNull { it.toolName == failedTool } ?: executionHistory.last()
            return deriveConciseResponse(
                toolName = failedTool,
                observationText = failedExec.observationText,
                success = false,
                errorCode = errCode,
                rawArgs = failedExec.rawArgs,
            )
        }

        val lastExec = executionHistory.last()
        return deriveConciseResponse(
            toolName = lastExec.toolName,
            observationText = lastExec.observationText,
            success = lastExec.success,
            errorCode = null,
            rawArgs = lastExec.rawArgs,
        )
    }

    /**
     * Fallback for GoalEvent.Completed / ChatViewModel when all prose is missing or generic.
     */
    fun deriveFallback(executionResult: ExecutionResult?, summary: String?): String {
        val candidate = summary?.takeIf { !isGenericFallback(it) }
            ?: executionResult?.userMessage?.takeIf { !isGenericFallback(it) }
            ?: executionResult?.message?.takeIf { !isGenericFallback(it) }

        if (candidate != null) return sanitizeArchitectureConcepts(candidate)

        val completed = executionResult?.completedSteps.orEmpty()
        val raw = if (completed.isNotEmpty()) {
            "Actions executed: ${completed.joinToString()}."
        } else {
            "Action finished with no additional output."
        }
        return sanitizeArchitectureConcepts(raw)
    }
}
