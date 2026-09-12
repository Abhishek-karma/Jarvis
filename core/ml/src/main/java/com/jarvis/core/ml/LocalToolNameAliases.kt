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
        // Observed on-device Gemma output: "devicecontrol:createfile" → create_file.
        put("devicecontrolcreatefile", "create_file")
        put("devicecontrolreadfile", "read_file")
        put("devicecontrolsearchfiles", "search_files")
        put("createfile", "create_file")
        put("readfile", "read_file")
        put("searchfiles", "search_files")
        put("battery", "battery_level")
        put("batterylevel", "battery_level")
        put("getbattery", "battery_level")
        put("currenttime", "get_current_datetime")
        put("currentdatetime", "get_current_datetime")
        put("gettime", "get_current_datetime")
        put("calculator", "calculator")
        put("calc", "calculator")
        put("launchapp", "launch_app")
        put("searchweb", "search_web")
        put("websearch", "search_web")
        put("fetchurl", "fetch_url")
    }

    /** Resolves a raw model-emitted name to its canonical registry name, or null when unknown. */
    fun resolve(rawName: String?): String? {
        if (rawName.isNullOrBlank()) return null
        val normalized = normalize(rawName)
        return aliases[normalized]
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
