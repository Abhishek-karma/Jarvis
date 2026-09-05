package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult

/**
 * Alarm tools (v0.5 catalog, `06-AGENT §3`): set a one-shot alarm. Reversible-write tier:
 * an alarm is user-visible and dismissable, but it does change device state, so the
 * confirmation gate applies when cautious mode is on. The AlarmManager sender is injected
 * as a lambda so the tool is JVM-unit-testable.
 */
object AlarmTools {
    const val SET_ALARM = "set_alarm"

    val manifestNames: List<String> = listOf(SET_ALARM)

    fun all(
        setAlarm: suspend (triggerAtUtcMillis: Long, label: String) -> Result<Unit>,
    ): List<Tool> = listOf(setAlarm(setAlarm))

    fun setAlarm(set: suspend (triggerAtUtcMillis: Long, label: String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = SET_ALARM
            override val description =
                "Set a one-shot alarm for a specific time (epoch millis) with a short label. " +
                    "The alarm rings even if the app is closed; the user can dismiss it."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = ALARM_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val at = args.long("at_utc_millis")
                val label = args.string("label")?.trim().orEmpty().ifBlank { "Jarvis alarm" }
                if (at == null) {
                    return ToolResult(
                        success = false,
                        observationText =
                            "Missing argument: at_utc_millis (epoch milliseconds) is required.",
                        error = "at_utc_millis is required",
                    )
                }
                if (at <= System.currentTimeMillis() + MIN_LEAD_MS) {
                    return ToolResult(
                        success = false,
                        observationText =
                            "The alarm time is in the past or less than a minute away — " +
                                "pick a future time.",
                        error = "alarm time must be at least a minute in the future",
                    )
                }
                return set(at, label.take(MAX_LABEL_CHARS)).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Alarm \"$label\" set.",
                            structuredData = mapOf("at_utc_millis" to at, "label" to label),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not set the alarm.",
                            error = error.message ?: "Alarm set failed",
                        )
                    },
                )
            }
        }

    internal const val MIN_LEAD_MS = 60_000L
    internal const val MAX_LABEL_CHARS = 50

    private const val ALARM_SCHEMA =
        """{"type":"object","properties":{"at_utc_millis":{"type":"number"},"label":{"type":"string"}},"required":["at_utc_millis"]}"""
}
