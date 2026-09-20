package com.jarvis.core.agent

import com.jarvis.core.agent.tools.DeviceTools
import com.jarvis.core.agent.tools.MediaTools
import com.jarvis.core.agent.tools.SystemInfoTools
import java.util.Locale

/** Small deterministic optimization for unambiguous single-step requests. */
sealed interface DirectCapability {
    val toolName: String
    val argsJson: String
    val userFacingAction: String
    data class Read(override val toolName: String, override val argsJson: String = "{}", override val userFacingAction: String) : DirectCapability
    data class Action(override val toolName: String, override val argsJson: String, override val userFacingAction: String) : DirectCapability
}

class CapabilityResolver {
    fun resolve(text: String): DirectCapability? {
        val value = text.trim().lowercase(Locale.US)
        if (value.isBlank()) return null
        return when {
            value.matches(Regex("""(what('?s| is) )?(my )?battery( level| percentage)?\\?""")) ->
                DirectCapability.Read(SystemInfoTools.BATTERY_LEVEL, userFacingAction = "Checking your battery…")
            value.matches(Regex("""(what('?s| is) )?(the )?(current )?(time|date and time)\\?""")) ->
                DirectCapability.Read(SystemInfoTools.GET_CURRENT_DATETIME, userFacingAction = "Checking the time…")
            value.matches(Regex("""(how much|what).*storage.*(free|left).*""")) ->
                DirectCapability.Read(SystemInfoTools.STORAGE_FREE, userFacingAction = "Checking storage…")
            value.matches(Regex("""(what('?s| is) )?(my )?(network|wifi)( status)?\\?""")) ->
                DirectCapability.Read(SystemInfoTools.NETWORK_STATUS, userFacingAction = "Checking your network…")
            value.matches(Regex("""(turn|set|make).*volume.*(up|louder)""")) ->
                DirectCapability.Action(MediaTools.ADJUST_VOLUME, """{"action":"up"}""", "Increasing the volume…")
            value.matches(Regex("""(turn|set|make).*volume.*(down|lower|quieter)""")) ->
                DirectCapability.Action(MediaTools.ADJUST_VOLUME, """{"action":"down"}""", "Decreasing the volume…")
            value.matches(Regex("""(mute|silence).*volume.*""")) ->
                DirectCapability.Action(MediaTools.ADJUST_VOLUME, """{"action":"mute"}""", "Muting the volume…")
            value.matches(Regex("""(unmute|unsilence).*volume.*""")) ->
                DirectCapability.Action(MediaTools.ADJUST_VOLUME, """{"action":"unmute"}""", "Unmuting the volume…")
            value.startsWith("open ") || value.startsWith("launch ") -> {
                val app = value.substringAfter(' ').trim()
                if (app.isBlank()) null else DirectCapability.Action(DeviceTools.LAUNCH_APP, "{\"app_name\":\"" + jsonEscape(app) + "\"}", "Opening the app…")
            }
            else -> null
        }
    }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
