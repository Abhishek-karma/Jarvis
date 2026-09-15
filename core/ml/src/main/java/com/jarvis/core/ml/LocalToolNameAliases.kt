package com.jarvis.core.ml

import com.jarvis.core.network.ToolDefinition

/**
 * Allowlisted canonical mapping from raw tool names a small on-device model may emit to the
 * canonical Jarvis [ToolDefinition.name] registered in the ToolRegistry. Only exact allowlist
 * entries resolve; anything else is rejected as an unknown tool.
 */
object LocalToolNameAliases {
    /** Model-emitted raw names → canonical names (keys squashed lowercase; see [normalize]). */
    private val aliases: Map<String, String> = buildMap {
        // Files
        put("createfile", "create_file")
        put("readfile", "read_file")
        put("searchfiles", "search_files")
        put("listfiles", "search_files")
        put("devicecontrolcreatefile", "create_file")
        put("devicecontrolreadfile", "read_file")
        put("devicecontrolsearchfiles", "search_files")
        put("devicecontrollistfiles", "search_files")

        // Battery
        put("battery", "battery_level")
        put("batterylevel", "battery_level")
        put("getbattery", "battery_level")
        put("getbatterylevel", "battery_level")
        put("checkbattery", "battery_level")
        put("devicecontrolbattery", "battery_level")
        put("devicecontrolbatterylevel", "battery_level")
        put("devicecontrolgetbattery", "battery_level")

        // Date / Time
        put("currenttime", "get_current_datetime")
        put("currentdatetime", "get_current_datetime")
        put("gettime", "get_current_datetime")
        put("getdatetime", "get_current_datetime")
        put("getcurrentdatetime", "get_current_datetime")
        put("getcurrenttime", "get_current_datetime")
        put("datetime", "get_current_datetime")
        put("time", "get_current_datetime")
        put("devicecontrolcurrenttime", "get_current_datetime")
        put("devicecontrolcurrentdatetime", "get_current_datetime")
        put("devicecontrolgettime", "get_current_datetime")
        put("devicecontrolgetdatetime", "get_current_datetime")
        put("devicecontrolgetcurrentdatetime", "get_current_datetime")

        // Calculator
        put("calculator", "calculator")
        put("calc", "calculator")
        put("calculate", "calculator")
        put("eval", "calculator")
        put("math", "calculator")
        put("devicecontrolcalculator", "calculator")
        put("devicecontrolcalc", "calculator")
        put("devicecontrolcalculate", "calculator")

        // App Launch
        put("launchapp", "launch_app")
        put("openapp", "launch_app")
        put("opencamera", "launch_app")
        put("camera", "launch_app")
        put("devicecontrollaunchapp", "launch_app")
        put("devicecontrolopenapp", "launch_app")
        put("devicecontrolopencamera", "launch_app")
        put("devicecontrolcamera", "launch_app")

        // Web Search & Fetch
        put("searchweb", "search_web")
        put("websearch", "search_web")
        put("search", "search_web")
        put("googlesearch", "search_web")
        put("google", "search_web")
        put("devicecontrolsearchweb", "search_web")
        put("devicecontrolsearch", "search_web")
        put("fetchurl", "fetch_url")
        put("browseurl", "fetch_url")

        // Communications
        put("sendsms", "send_sms")
        put("sendmessage", "send_sms")
        put("devicecontrolsendsms", "send_sms")
    }

    /** Resolves a raw model-emitted name to its canonical registry name, or null when unknown. */
    fun resolve(rawName: String?): String? {
        if (rawName.isNullOrBlank()) return null
        val normalized = normalize(rawName)
        aliases[normalized]?.let { return it }
        val stripped = rawName.substringAfterLast(':').substringAfterLast('.')
        if (stripped != rawName) {
            val strippedNormalized = normalize(stripped)
            aliases[strippedNormalized]?.let { return it }
        }
        return null
    }

    /** True when [rawName] is exactly a registered canonical name or an allowlisted alias. */
    fun isKnown(rawName: String?, registeredNames: Set<String>): Boolean {
        if (rawName.isNullOrBlank()) return false
        if (rawName in registeredNames) return true
        val canonical = resolve(rawName) ?: return false
        return canonical in registeredNames
    }

    internal fun normalize(rawName: String): String =
        rawName.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(':', ' ')
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("")
}
