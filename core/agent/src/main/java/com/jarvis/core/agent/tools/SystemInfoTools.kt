package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object SystemInfoTools {
    const val BATTERY_LEVEL = "battery_level"
    const val STORAGE_FREE = "storage_free"
    const val NETWORK_STATUS = "network_status"
    const val CURRENT_TIME = "current_time"
    const val GET_CURRENT_DATETIME = "get_current_datetime"

    val manifestNames: List<String> = listOf(BATTERY_LEVEL, STORAGE_FREE, NETWORK_STATUS, CURRENT_TIME, GET_CURRENT_DATETIME)

    /** All system-info tools, wired to the platform readers the host app provides. */
    fun all(
        batteryPercent: () -> Int?,
        storageFreeBytes: () -> Long?,
        networkState: () -> String,
    ): List<Tool> =
        listOf(
            batteryLevel(batteryPercent),
            storageFree(storageFreeBytes),
            networkStatus(networkState),
            currentTime(),
            getCurrentDateTime(),
        )

    fun batteryLevel(percent: () -> Int?): Tool =
        object : Tool {
            override val name = BATTERY_LEVEL
            override val description = "Current battery charge percentage (0-100) of this device. Read-only."
            override val parametersSchemaJson = EMPTY_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                val level = percent()
                return if (level != null) {
                    ToolResult(
                        success = true,
                        observationText = "Battery at $level%.",
                        structuredData = mapOf("percent" to level),
                    )
                } else {
                    ToolResult(
                        success = false,
                        observationText = "Battery level unavailable.",
                        error = "No battery present or level could not be read",
                    )
                }
            }
        }

    fun storageFree(freeBytes: () -> Long?): Tool =
        object : Tool {
            override val name = STORAGE_FREE
            override val description = "Free device storage in bytes. Read-only."
            override val parametersSchemaJson = EMPTY_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                val free = freeBytes()
                return if (free != null) {
                    ToolResult(
                        success = true,
                        observationText = "Free storage: $free bytes.",
                        structuredData = mapOf("freeBytes" to free),
                    )
                } else {
                    ToolResult(
                        success = false,
                        observationText = "Storage free-space read failed.",
                        error = "StatFs read failed",
                    )
                }
            }
        }

    fun networkStatus(state: () -> String): Tool =
        object : Tool {
            override val name = NETWORK_STATUS
            override val description = "Current network state: wifi, cellular, or offline. Read-only."
            override val parametersSchemaJson = EMPTY_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult =
                ToolResult(
                    success = true,
                    observationText = "Network state: ${state()}.",
                    structuredData = mapOf("state" to state()),
                )
        }

    fun currentTime(nowUtcMillis: () -> Long = System::currentTimeMillis): Tool =
        object : Tool {
            override val name = CURRENT_TIME
            override val description = "The current date and time in UTC (ISO-8601). Read-only."
            override val parametersSchemaJson = EMPTY_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                val iso = Instant.ofEpochMilli(nowUtcMillis()).toString()
                return ToolResult(
                    success = true,
                    observationText = "Current time: $iso (UTC).",
                    structuredData = mapOf("utcIso" to iso),
                )
            }
        }

    fun getCurrentDateTime(
        nowMillis: () -> Long = System::currentTimeMillis,
        zoneId: () -> ZoneId = ZoneId::systemDefault,
    ): Tool =
        object : Tool {
            override val name = GET_CURRENT_DATETIME
            override val description =
                "Get the current local date, time, timezone, and day of week. Read-only. Does not require internet."
            override val parametersSchemaJson = EMPTY_SCHEMA
            override val tier = PermissionTier.READ_ONLY

            override suspend fun execute(argsJson: String): ToolResult {
                val zone = runCatching { zoneId() }.getOrDefault(ZoneId.systemDefault())
                val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis()), zone)
                val dateStr = zdt.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val timeStr = zdt.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US))
                val dayOfWeek = zdt.dayOfWeek.name.lowercase(Locale.US)
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                val timezone = zone.id
                val iso = zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

                val text = "Current date and time: $dayOfWeek, $dateStr $timeStr ($timezone, $iso)"
                return ToolResult(
                    success = true,
                    observationText = text,
                    structuredData = mapOf(
                        "date" to dateStr,
                        "time" to timeStr,
                        "day_of_week" to dayOfWeek,
                        "timezone" to timezone,
                        "iso" to iso,
                    ),
                )
            }
        }

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{},"required":[]}"""
}
