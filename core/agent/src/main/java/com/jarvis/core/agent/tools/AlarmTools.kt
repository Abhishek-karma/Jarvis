package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier


object AlarmTools {
    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"

    val manifestNames: List<String> = listOf(SET_ALARM, SET_TIMER)

    fun all(
        setAlarm: suspend (triggerAtUtcMillis: Long, label: String) -> Result<Unit>,
        setTimer: (suspend (durationSeconds: Int, label: String) -> Result<Unit>)? = null,
    ): List<Tool> = buildList {
        add(setAlarm(setAlarm))
        if (setTimer != null) {
            add(setTimer(setTimer))
        }
    }

    fun setTimer(set: suspend (durationSeconds: Int, label: String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = SET_TIMER
            override val description =
                "Set a countdown timer for a duration in seconds with a label. Reversible write."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = TIMER_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val duration = args.int("duration_seconds")
                    ?: args.int("durationSeconds")
                    ?: args.int("seconds")
                    ?: args.int("duration")
                val label = args.string("label")?.trim().orEmpty().ifBlank { "Jarvis timer" }
                if (duration == null || duration <= 0) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing or invalid duration_seconds (must be positive).",
                        error = "duration_seconds is required and must be > 0",
                    )
                }
                return set(duration, label.take(MAX_LABEL_CHARS)).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Timer for $duration seconds (\"$label\") started.",
                            structuredData = mapOf("duration_seconds" to duration, "label" to label),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not start timer.",
                            error = error.message ?: "Timer set failed",
                        )
                    },
                )
            }
        }

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
                    ?: args.long("atUtcMillis")
                    ?: args.long("timestamp")
                    ?: args.long("time")
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
    private const val TIMER_SCHEMA =
        """{"type":"object","properties":{"duration_seconds":{"type":"integer"},"label":{"type":"string"}},"required":["duration_seconds"]}"""
}
