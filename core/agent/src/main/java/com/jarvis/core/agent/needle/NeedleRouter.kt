package com.jarvis.core.agent.needle

import com.jarvis.core.agent.tools.DeviceTools
import com.jarvis.core.agent.tools.MediaTools
import com.jarvis.core.agent.tools.SystemInfoTools
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Needle 3 Fast Capability Router for Jarvis.
 *
 * Routes incoming user prompts on-device:
 * - High-confidence simple capabilities -> Direct execution via existing ToolRegistry & ToolPolicy
 * - Low-confidence, unsupported, complex, or multi-step requests -> Escalate to AgentRunner & LLM
 */
@Singleton
open class NeedleRouter @Inject constructor(
    private val engine: NeedleEngine = NeedleEngine(),
    private val config: NeedleConfig = NeedleConfig(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    open suspend fun route(prompt: String): RoutingDecision = withContext(defaultDispatcher) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) {
            return@withContext RoutingDecision.Escalate(EscalationReason.EMPTY_PROMPT)
        }

        val toolCall = try {
            engine.route(trimmed, NeedleToolCatalogue.ALL_TOOLS)
        } catch (e: Exception) {
            return@withContext RoutingDecision.Escalate(EscalationReason.MALFORMED_RESULT, e.message)
        }

        if (toolCall == null) {
            val clean = trimmed.trimEnd('.', '!', '?', ';', ',')
            val lower = clean.lowercase(java.util.Locale.US)
            val appIntent = engine.parseAppLaunchIntent(clean, lower)
            return@withContext if (appIntent != null && appIntent.requiresAutomation) {
                RoutingDecision.Escalate(
                    EscalationReason.COMPLEX_OR_MULTI_STEP,
                    "Multi-step automation goal for ${appIntent.appName}: ${appIntent.userGoal}",
                )
            } else {
                RoutingDecision.Escalate(EscalationReason.UNSUPPORTED)
            }
        }

        if (toolCall.confidence < config.confidenceThreshold) {
            return@withContext RoutingDecision.Escalate(
                EscalationReason.LOW_CONFIDENCE,
                "Confidence ${toolCall.confidence} below threshold ${config.confidenceThreshold}",
            )
        }

        // Map Needle capability name to Jarvis ToolRegistry name & user facing description
        val mapped = mapToJarvisTool(toolCall)
            ?: return@withContext RoutingDecision.Escalate(EscalationReason.UNSUPPORTED, "Unknown tool ${toolCall.name}")

        mapped
    }

    private fun mapToJarvisTool(call: NeedleToolCall): RoutingDecision.Direct? {
        return when (call.name) {
            "launch_app" -> {
                val appName = extractStringArg(call.argumentsJson, "app_name") ?: "app"
                RoutingDecision.Direct(
                    toolName = DeviceTools.LAUNCH_APP,
                    argsJson = call.argumentsJson,
                    confidence = call.confidence,
                    userFacingAction = "Opening $appName…",
                )
            }
            "open_settings" -> {
                val settingType = extractStringArg(call.argumentsJson, "setting_type") ?: "general"
                val label = if (settingType == "general") "Settings" else "$settingType settings"
                RoutingDecision.Direct(
                    toolName = DeviceTools.OPEN_SETTINGS,
                    argsJson = call.argumentsJson,
                    confidence = call.confidence,
                    userFacingAction = "Opening $label…",
                )
            }
            "set_flashlight" -> {
                val enabled = call.argumentsJson.contains("\"enabled\":true") || call.argumentsJson.contains("\"enabled\": true")
                RoutingDecision.Direct(
                    toolName = DeviceTools.SET_FLASHLIGHT,
                    argsJson = call.argumentsJson,
                    confidence = call.confidence,
                    userFacingAction = if (enabled) "Turning on flashlight…" else "Turning off flashlight…",
                )
            }
            "get_battery", "battery_level" -> {
                RoutingDecision.Direct(
                    toolName = SystemInfoTools.BATTERY_LEVEL,
                    argsJson = "{}",
                    confidence = call.confidence,
                    userFacingAction = "Checking battery…",
                )
            }
            "get_time", "get_current_datetime" -> {
                RoutingDecision.Direct(
                    toolName = SystemInfoTools.GET_CURRENT_DATETIME,
                    argsJson = "{}",
                    confidence = call.confidence,
                    userFacingAction = "Checking the time…",
                )
            }
            "adjust_volume" -> {
                val action = extractStringArg(call.argumentsJson, "action") ?: "up"
                val label = when (action) {
                    "up" -> "Increasing volume…"
                    "down" -> "Decreasing volume…"
                    "mute" -> "Muting volume…"
                    "unmute" -> "Unmuting volume…"
                    else -> "Adjusting volume…"
                }
                RoutingDecision.Direct(
                    toolName = MediaTools.ADJUST_VOLUME,
                    argsJson = call.argumentsJson,
                    confidence = call.confidence,
                    userFacingAction = label,
                )
            }
            "play_media" -> {
                val query = extractStringArg(call.argumentsJson, "query") ?: "music"
                val app = extractStringArg(call.argumentsJson, "app_name")
                val label = if (app != null) "Playing $query on $app…" else "Playing $query…"
                RoutingDecision.Direct(
                    toolName = MediaTools.PLAY_MEDIA,
                    argsJson = call.argumentsJson,
                    confidence = call.confidence,
                    userFacingAction = label,
                )
            }
            "media_control" -> {
                val action = extractStringArg(call.argumentsJson, "action") ?: "play"
                RoutingDecision.Direct(
                    toolName = MediaTools.MEDIA_CONTROL,
                    argsJson = call.argumentsJson,
                    confidence = call.confidence,
                    userFacingAction = "Media control ($action)…",
                )
            }
            "storage_free" -> {
                RoutingDecision.Direct(
                    toolName = SystemInfoTools.STORAGE_FREE,
                    argsJson = "{}",
                    confidence = call.confidence,
                    userFacingAction = "Checking storage…",
                )
            }
            "network_status" -> {
                RoutingDecision.Direct(
                    toolName = SystemInfoTools.NETWORK_STATUS,
                    argsJson = "{}",
                    confidence = call.confidence,
                    userFacingAction = "Checking network status…",
                )
            }
            "install_app" -> {
                val appName = extractStringArg(call.argumentsJson, "app_name") ?: "app"
                RoutingDecision.Direct(
                    toolName = DeviceTools.INSTALL_APP,
                    argsJson = call.argumentsJson,
                    confidence = call.confidence,
                    userFacingAction = "Opening Google Play Store to install $appName…",
                )
            }
            "list_installed_apps" -> {
                RoutingDecision.Direct(
                    toolName = DeviceTools.LIST_INSTALLED_APPS,
                    argsJson = "{}",
                    confidence = call.confidence,
                    userFacingAction = "Checking installed apps…",
                )
            }
            else -> null
        }
    }

    private fun extractStringArg(json: String, key: String): String? {
        val pattern = Regex("""\"$key\"\s*:\s*\"([^\"]+)\"""")
        return pattern.find(json)?.groupValues?.get(1)
    }
}
