package com.jarvis.core.agent.needle

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast capability router for Jarvis.
 *
 * IMPORTANT: This is NOT a neural model. No on-device Needle model or native engine is bundled in
 * the repository, so this engine performs small, deterministic keyword-based capability routing and
 * escalates anything complex or unsupported to [NeedleRouter]/AgentRunner. It exists purely as a
 * lightweight, predictable fallback router — not as autonomous planning or general LLM reasoning.
 */
@Singleton
class NeedleEngine @Inject constructor(
    private val config: NeedleConfig = NeedleConfig(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Routes a prompt to a single predicted capability, or null when the request is complex,
     * multi-step, unsupported, or not confidently mappable to a reliable native tool.
     */
    suspend fun route(prompt: String, tools: List<NeedleToolDef>): NeedleToolCall? = withContext(defaultDispatcher) {
        val clean = prompt.trim().trimEnd('.', '!', '?', ';', ',')
        val lower = clean.lowercase(Locale.US)
        if (lower.isBlank()) return@withContext null

        // small curated capability catalogue; complex or multi-step requests are deliberately not
        // mapped here and must escalate to the AgentRunner.
        predictEmbedded(clean, lower, tools)
    }

    private fun predictEmbedded(clean: String, lower: String, tools: List<NeedleToolDef>): NeedleToolCall? {
        // Multi-step, complex, or conversational requests must NOT be mapped to single tools
        if (isComplexOrMultiStep(lower)) {
            return null
        }

        // 1. Settings capability
        predictSettings(lower)?.let { return it }

        // 2. Flashlight capability
        predictFlashlight(lower)?.let { return it }

        // 3. Battery capability
        predictBattery(lower)?.let { return it }

        // 4. Time capability
        predictTime(lower)?.let { return it }

        // 5. Volume capability
        predictVolume(lower)?.let { return it }

        // 6. Media / Music playback and control capability
        predictMedia(clean, lower)?.let { return it }

        // 7. Storage capability
        predictStorage(lower)?.let { return it }

        // 8. Network status capability
        predictNetwork(lower)?.let { return it }

        // 9. App install / Play Store capability
        predictInstall(clean, lower)?.let { return it }

        // 10. List installed apps capability
        predictListApps(lower)?.let { return it }

        // 11. App launch capability (strictly for simple, single-action app launches)
        predictAppLaunch(clean, lower)?.let { return it }

        return null
    }

    private fun isComplexOrMultiStep(lower: String): Boolean {
        // Punctuation sequences indicating multiple sub-tasks
        if (lower.contains(",") || lower.contains(";")) {
            return true
        }

        // Conjunctions indicating chained operations
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after that ") || lower.contains(" also ")) {
            return true
        }

        return false
    }

    private fun predictSettings(lower: String): NeedleToolCall? {
        val hasSettingsKeyword = lower.contains("settings") || lower.startsWith("open ") || lower.startsWith("show ") || lower.startsWith("go to ") || lower.startsWith("take me to ")
        if (!hasSettingsKeyword && lower != "settings") return null

        if (lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("internet") || lower.contains("network")) {
            return NeedleToolCall(
                name = "open_settings",
                argumentsJson = """{"setting_type":"wifi"}""",
                confidence = 0.96f,
            )
        }

        if (lower.contains("bluetooth")) {
            return NeedleToolCall(
                name = "open_settings",
                argumentsJson = """{"setting_type":"bluetooth"}""",
                confidence = 0.96f,
            )
        }

        if (lower.contains("display") || lower.contains("screen") || lower.contains("brightness")) {
            return NeedleToolCall(
                name = "open_settings",
                argumentsJson = """{"setting_type":"display"}""",
                confidence = 0.95f,
            )
        }

        if (lower.contains("sound") || lower.contains("audio") || lower.contains("volume")) {
            return NeedleToolCall(
                name = "open_settings",
                argumentsJson = """{"setting_type":"sound"}""",
                confidence = 0.95f,
            )
        }

        if (lower.contains("battery") || lower.contains("power")) {
            if (lower.contains("settings")) {
                return NeedleToolCall(
                    name = "open_settings",
                    argumentsJson = """{"setting_type":"battery"}""",
                    confidence = 0.94f,
                )
            }
        }

        if (lower.contains("accessibility")) {
            return NeedleToolCall(
                name = "open_settings",
                argumentsJson = """{"setting_type":"accessibility"}""",
                confidence = 0.96f,
            )
        }

        if (lower == "settings" || lower.endsWith("settings") || lower.contains("system settings") || lower.contains("device settings")) {
            return NeedleToolCall(
                name = "open_settings",
                argumentsJson = """{"setting_type":"general"}""",
                confidence = 0.95f,
            )
        }

        return null
    }

    private fun predictFlashlight(lower: String): NeedleToolCall? {
        val mentionsFlash = lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light")
        if (!mentionsFlash) return null

        val isTurnOn = lower.contains("on") || lower.contains("enable") || lower.contains("start") || lower.contains("activate")
        val isTurnOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop") || lower.contains("deactivate")

        return when {
            isTurnOn && !isTurnOff -> NeedleToolCall("set_flashlight", """{"enabled":true}""", 0.98f)
            isTurnOff && !isTurnOn -> NeedleToolCall("set_flashlight", """{"enabled":false}""", 0.98f)
            else -> null
        }
    }

    private fun predictBattery(lower: String): NeedleToolCall? {
        if (!lower.contains("battery")) return null
        if (lower.contains("settings")) return null // handled by settings

        val isQuery = lower.contains("level") || lower.contains("percent") || lower.contains("how much") ||
            lower.contains("what is") || lower.contains("status") || lower.contains("left") || lower == "battery"
        if (isQuery) {
            return NeedleToolCall("get_battery", "{}", 0.97f)
        }
        return null
    }

    private fun predictTime(lower: String): NeedleToolCall? {
        if (!lower.contains("time") && !lower.contains("date") && !lower.contains("clock")) return null

        val isTimeQuery = lower.contains("what") || lower.contains("current") || lower.contains("tell me") ||
            lower.contains("now") || lower.contains("today") || lower == "time" || lower == "what time is it"
        if (isTimeQuery) {
            return NeedleToolCall("get_time", "{}", 0.97f)
        }
        return null
    }

    private fun predictVolume(lower: String): NeedleToolCall? {
        if (!lower.contains("volume") && !lower.contains("sound") && !lower.contains("audio")) return null
        if (lower.contains("settings")) return null

        return when {
            lower.contains("up") || lower.contains("increase") || lower.contains("louder") || lower.contains("raise") ->
                NeedleToolCall("adjust_volume", """{"action":"up"}""", 0.96f)
            lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("quieter") ->
                NeedleToolCall("adjust_volume", """{"action":"down"}""", 0.96f)
            lower.contains("unmute") ->
                NeedleToolCall("adjust_volume", """{"action":"unmute"}""", 0.96f)
            lower.contains("mute") || lower.contains("silence") ->
                NeedleToolCall("adjust_volume", """{"action":"mute"}""", 0.96f)
            else -> null
        }
    }

    private fun predictMedia(clean: String, lower: String): NeedleToolCall? {
        // Playback control commands
        if (lower == "pause" || lower == "pause music" || lower == "pause playback" || lower == "pause song") {
            return NeedleToolCall("media_control", """{"action":"pause"}""", 0.98f)
        }
        if (lower == "resume" || lower == "resume music" || lower == "resume playback" || lower == "unpause") {
            return NeedleToolCall("media_control", """{"action":"play"}""", 0.98f)
        }
        if (lower == "stop music" || lower == "stop playback" || lower == "stop song") {
            return NeedleToolCall("media_control", """{"action":"stop"}""", 0.98f)
        }
        if (lower == "next song" || lower == "next track" || lower == "skip song" || lower == "skip track") {
            return NeedleToolCall("media_control", """{"action":"next"}""", 0.98f)
        }
        if (lower == "previous song" || lower == "prev song" || lower == "previous track") {
            return NeedleToolCall("media_control", """{"action":"previous"}""", 0.98f)
        }

        // Play commands: "play jazz", "play music", "play blinding lights on spotify", "play lofi"
        val playPrefixes = listOf(
            "can you please play ",
            "please play ",
            "can you play ",
            "could you play ",
            "play some ",
            "play ",
            "stream ",
        )
        for (prefix in playPrefixes) {
            if (lower.startsWith(prefix)) {
                val tail = clean.substring(prefix.length).trim()
                if (tail.isNotBlank()) {
                    var targetApp: String? = null
                    var finalQuery = tail
                    val onAppRegex = Regex("""\s+on\s+(spotify|youtube music|yt music|youtube|apple music|soundcloud)$""", RegexOption.IGNORE_CASE)
                    val onAppMatch = onAppRegex.find(tail)
                    if (onAppMatch != null) {
                        targetApp = onAppMatch.groupValues[1].lowercase(Locale.US)
                        finalQuery = tail.substring(0, onAppMatch.range.first).trim()
                    }
                    val appJson = if (targetApp != null) ""","app_name":"${escapeJson(targetApp)}"""" else ""
                    return NeedleToolCall(
                        name = "play_media",
                        argumentsJson = """{"query":"${escapeJson(finalQuery)}"$appJson}""",
                        confidence = 0.95f,
                    )
                }
            }
        }
        return null
    }

    private fun predictStorage(lower: String): NeedleToolCall? {
        if (!lower.contains("storage") && !lower.contains("space") && !lower.contains("memory")) return null
        if (lower.contains("free") || lower.contains("left") || lower.contains("available") || lower.contains("how much")) {
            return NeedleToolCall("storage_free", "{}", 0.95f)
        }
        return null
    }

    private fun predictNetwork(lower: String): NeedleToolCall? {
        if ((lower.contains("network") || lower.contains("wifi") || lower.contains("internet") || lower.contains("connection")) &&
            (lower.contains("status") || lower.contains("connected") || lower.contains("check"))
        ) {
            return NeedleToolCall("network_status", "{}", 0.95f)
        }
        return null
    }

    private fun predictInstall(clean: String, lower: String): NeedleToolCall? {
        val prefixes = listOf("download ", "install ", "get ")
        val matchedPrefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return null

        val raw = clean.substring(matchedPrefix.length).trim()
        val targetApp = raw.lowercase(Locale.US)
            .removePrefix("the ")
            .removeSuffix(" from play store")
            .removeSuffix(" from google play")
            .removeSuffix(" on play store")
            .removeSuffix(" on google play")
            .removeSuffix(" app")
            .removeSuffix(" application")
            .trim()

        if (targetApp.isNotBlank() && targetApp != "apps" && targetApp != "settings") {
            return NeedleToolCall(
                name = "install_app",
                argumentsJson = """{"app_name":"${escapeJson(targetApp)}"}""",
                confidence = 0.94f,
            )
        }
        return null
    }

    private fun predictListApps(lower: String): NeedleToolCall? {
        if (lower.contains("installed apps") || lower.contains("what apps") || lower.contains("list apps") ||
            lower.contains("show apps") || lower == "apps"
        ) {
            return NeedleToolCall("list_installed_apps", "{}", 0.95f)
        }
        return null
    }

    /**
     * Semantic result of parsing an app launch or app automation request.
     */
    internal data class AppLaunchIntent(
        val appName: String,
        val userGoal: String? = null,
        val requiresAutomation: Boolean = false,
    )

    /**
     * Dissects an app request into the target application and any secondary user goal.
     * Separates single-action app launches from multi-step automation goals.
     */
    internal fun parseAppLaunchIntent(clean: String, lower: String): AppLaunchIntent? {
        val prefixes = listOf(
            "can you please open ",
            "could you please open ",
            "please open ",
            "can you open ",
            "could you open ",
            "can you launch ",
            "please launch ",
            "open ",
            "launch ",
            "start ",
            "run ",
            "take me to ",
            "go to ",
            "bring up ",
        )

        var matchedPrefix: String? = null
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                matchedPrefix = prefix
                break
            }
        }

        if (matchedPrefix == null) return null

        val tail = clean.substring(matchedPrefix.length).trim()
        if (tail.isBlank()) return null

        // Check for conjunction / punctuation delimiters e.g. "Instagram and search OpenAI"
        val delimiterRegex = Regex("""\s+(and|then|after that|also|to)\s+|[,;]\s*""", RegexOption.IGNORE_CASE)
        val delimiterMatch = delimiterRegex.find(tail)

        if (delimiterMatch != null) {
            val appPart = tail.substring(0, delimiterMatch.range.first).trim()
            val goalPart = tail.substring(delimiterMatch.range.last + 1).trim()
            val normalizedApp = cleanAppName(appPart)
            if (normalizedApp.isNotBlank()) {
                return AppLaunchIntent(
                    appName = normalizedApp,
                    userGoal = goalPart.ifBlank { null },
                    requiresAutomation = goalPart.isNotBlank(),
                )
            }
        }

        // Check for secondary action verbs acting as clause boundaries without explicit conjunctions
        // e.g. "WhatsApp search Ravi", "Instagram search OpenAI", "YouTube find Android", "WhatsApp message Mom"
        val words = tail.split(Regex("""\s+"""))
        if (words.size >= 2) {
            val actionVerbs = setOf(
                "search", "find", "message", "msg", "send", "type", "click", "tap",
                "post", "play", "watch", "call", "text", "dm", "check", "show", "scroll", "download", "install", "look"
            )
            for (i in 1 until words.size) {
                val wordLower = words[i].lowercase(Locale.US)
                if (actionVerbs.contains(wordLower)) {
                    val appPart = words.subList(0, i).joinToString(" ").trim()
                    val goalPart = words.subList(i, words.size).joinToString(" ").trim()
                    val normalizedApp = cleanAppName(appPart)
                    if (normalizedApp.isNotBlank()) {
                        return AppLaunchIntent(
                            appName = normalizedApp,
                            userGoal = goalPart,
                            requiresAutomation = true,
                        )
                    }
                }
            }
        }

        // Single action simple app launch
        val normalizedApp = cleanAppName(tail)
        if (normalizedApp.isNotBlank()) {
            return AppLaunchIntent(
                appName = normalizedApp,
                userGoal = null,
                requiresAutomation = false,
            )
        }

        return null
    }

    private fun cleanAppName(raw: String): String {
        return raw.lowercase(Locale.US)
            .removePrefix("the ")
            .removePrefix("app ")
            .removeSuffix(" app")
            .removeSuffix(" application")
            .trim()
    }

    private fun predictAppLaunch(clean: String, lower: String): NeedleToolCall? {
        val intent = parseAppLaunchIntent(clean, lower) ?: return null

        // If the request requires multi-step automation, do NOT map it directly to launch_app!
        // It must escalate to AgentRunner to formulate the autonomous automation cycle.
        if (intent.requiresAutomation) {
            return null
        }

        val app = intent.appName
        if (app.isNotBlank() && app != "settings" && app != "apps" && !app.endsWith("settings")) {
            return NeedleToolCall(
                name = "launch_app",
                argumentsJson = """{"app_name":"${escapeJson(app)}"}""",
                confidence = 0.96f,
            )
        }

        return null
    }

    private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
