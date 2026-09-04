package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import java.time.Instant

/**
 * First batch of built-in tools — the "System info" category of 06-AGENT.md §3. Platform
 * access is injected as plain lambdas so every tool stays JVM-unit-testable; the agent UI
 * phase binds real Android readers (BatteryManager, StatFs, ConnectivityManager) behind them.
 */
object SystemInfoTools {

    const val BATTERY_LEVEL = "battery_level"
    const val STORAGE_FREE = "storage_free"
    const val NETWORK_STATUS = "network_status"
    const val CURRENT_TIME = "current_time"

    val manifestNames: List<String> = listOf(BATTERY_LEVEL, STORAGE_FREE, NETWORK_STATUS, CURRENT_TIME)

    /** All system-info tools, wired to the platform readers the host app provides. */
    fun all(
        batteryPercent: () -> Int?,
        storageFreeBytes: () -> Long?,
        networkState: () -> String, // "wifi" | "cellular" | "offline"
    ): List<Tool> = listOf(
        batteryLevel(batteryPercent),
        storageFree(storageFreeBytes),
        networkStatus(networkState),
        currentTime(),
    )

    fun batteryLevel(percent: () -> Int?): Tool = object : Tool {
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

    fun storageFree(freeBytes: () -> Long?): Tool = object : Tool {
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

    fun networkStatus(state: () -> String): Tool = object : Tool {
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

    fun currentTime(nowUtcMillis: () -> Long = System::currentTimeMillis): Tool = object : Tool {
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

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{},"required":[]}"""
}
