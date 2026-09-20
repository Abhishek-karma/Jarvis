package com.jarvis.core.capability

import android.content.Context
import com.jarvis.core.database.repository.ReversibleActionRepository
import com.jarvis.core.database.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves user goals to available capabilities.
 * This is the core of the capability-based architecture - it answers "WHAT can Jarvis do?"
 * rather than "HOW should Jarvis do it?"
 */
@Singleton
class CapabilityResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val reversibleActionRepository: ReversibleActionRepository,
    private val okHttpClient: OkHttpClient,
) {

    private val capabilities: Map<String, Capability> by lazy {
        buildCapabilitiesMap()
    }

    private fun buildCapabilitiesMap(): Map<String, Capability> {
        val map = mutableMapOf<String, Capability>()
        // Device capabilities
        map[CapabilityIds.DEVICE_STATE] = DeviceCapability(context)
        map[CapabilityIds.BATTERY] = DeviceCapability(context)
        map[CapabilityIds.STORAGE] = DeviceCapability(context)
        map[CapabilityIds.WIFI] = DeviceCapability(context)
        map[CapabilityIds.NETWORK] = DeviceCapability(context)
        map[CapabilityIds.TIME] = DeviceCapability(context)

        // Communication capabilities
        map[CapabilityIds.MESSAGES] = CommunicationCapability(context, reversibleActionRepository)
        map[CapabilityIds.PHONE_CALLS] = CommunicationCapability(context, reversibleActionRepository)
        map[CapabilityIds.CONTACTS] = CommunicationCapability(context, reversibleActionRepository)

        // Calendar capabilities
        map[CapabilityIds.CALENDAR_EVENTS] = CalendarCapability(context, reversibleActionRepository)
        map[CapabilityIds.CALENDAR_REMINDERS] = CalendarCapability(context, reversibleActionRepository)

        // App capabilities
        map[CapabilityIds.LAUNCH_APP] = AppCapability(context)
        map[CapabilityIds.APP_INTERACTION] = AppCapability(context)

        // File capabilities
        map[CapabilityIds.FILES_READ] = FileCapability(context)
        map[CapabilityIds.FILES_WRITE] = FileCapability(context)
        map[CapabilityIds.FILES_SEARCH] = FileCapability(context)
        map[CapabilityIds.FILES_DELETE] = FileCapability(context)

        // Media capabilities
        map[CapabilityIds.MEDIA_PLAYBACK] = MediaCapability(context)
        map[CapabilityIds.MEDIA_CONTROLS] = MediaCapability(context)

        // Web capabilities
        map[CapabilityIds.WEB_SEARCH] = WebCapability(okHttpClient)
        map[CapabilityIds.WEB_FETCH] = WebCapability(okHttpClient)
        map[CapabilityIds.WEB_BROWSE] = WebCapability(okHttpClient)

        // Notification capabilities
        map[CapabilityIds.NOTIFICATIONS_READ] = NotificationCapability(context)
        map[CapabilityIds.NOTIFICATIONS_ACTION] = NotificationCapability(context)

        // System capabilities
        map[CapabilityIds.SYSTEM_INTENTS] = SystemCapability(context)
        map[CapabilityIds.SYSTEM_SETTINGS] = SystemCapability(context)
        map[CapabilityIds.SYSTEM_TIME] = SystemCapability(context)

        // Automation capabilities
        map[CapabilityIds.UI_AUTOMATION] = AutomationCapability(context)

        return map
    }

    /**
     * Get all available capabilities
     */
    suspend fun getAllCapabilities(): List<Capability> = capabilities.values.toList()

    /**
     * Get a specific capability by ID
     */
    fun getCapability(id: String): Capability? = capabilities[id]

    /**
     * Resolve a goal description to the most relevant capabilities.
     * This is the key method that replaces keyword-based routing.
     */
    suspend fun resolveCapabilities(goalDescription: String): List<CapabilityMatch> {
        val matches = mutableListOf<CapabilityMatch>()
        val lowerGoal = goalDescription.lowercase()

        // Define capability matching rules based on intent patterns
        // These are semantic patterns, not keyword hacks
        val rules = listOf(
            // Battery/Device state
            CapabilityRule(
                id = CapabilityIds.BATTERY,
                patterns = listOf("battery", "power level", "charge level", "battery status"),
                weight = 1.0,
                requiredParams = mapOf("action" to "battery"),
            ),
            CapabilityRule(
                id = CapabilityIds.STORAGE,
                patterns = listOf("storage", "disk space", "free space", "disk usage", "memory usage"),
                weight = 1.0,
                requiredParams = mapOf("action" to "storage"),
            ),
            CapabilityRule(
                id = CapabilityIds.TIME,
                patterns = listOf("time", "what time", "current time", "date", "what date", "today"),
                weight = 1.0,
                requiredParams = mapOf("action" to "time"),
            ),
            CapabilityRule(
                id = CapabilityIds.NETWORK,
                patterns = listOf("wifi", "network", "internet", "connection", "online"),
                weight = 0.9,
                requiredParams = mapOf("action" to "network"),
            ),

            // Communication
            CapabilityRule(
                id = CapabilityIds.MESSAGES,
                patterns = listOf("send message", "send sms", "text ", "message ", "sms "),
                weight = 1.0,
                requiredParams = mapOf("action" to "send_sms"),
            ),
            CapabilityRule(
                id = CapabilityIds.PHONE_CALLS,
                patterns = listOf("call ", "phone ", "dial "),
                weight = 1.0,
                requiredParams = mapOf("action" to "call"),
            ),
            CapabilityRule(
                id = CapabilityIds.CONTACTS,
                patterns = listOf("contact", "phone number", "find contact", "lookup "),
                weight = 0.9,
                requiredParams = mapOf("action" to "lookup_contact"),
            ),

            // Calendar
            CapabilityRule(
                id = CapabilityIds.CALENDAR_EVENTS,
                patterns = listOf("calendar", "event", "meeting", "appointment", "schedule"),
                weight = 1.0,
                requiredParams = mapOf("action" to "query_events"),
            ),
            CapabilityRule(
                id = CapabilityIds.CALENDAR_REMINDERS,
                patterns = listOf("remind", "reminder", "alarm"),
                weight = 0.9,
                requiredParams = mapOf("action" to "create_reminder"),
            ),

            // App
            CapabilityRule(
                id = CapabilityIds.LAUNCH_APP,
                patterns = listOf("open ", "launch ", "start "),
                weight = 0.8,
                requiredParams = mapOf("action" to "launch_app"),
            ),

            // Files
            CapabilityRule(
                id = CapabilityIds.FILES_READ,
                patterns = listOf("read file", "open file", "view file"),
                weight = 0.9,
                requiredParams = mapOf("action" to "read"),
            ),
            CapabilityRule(
                id = CapabilityIds.FILES_WRITE,
                patterns = listOf("create file", "write file", "save file", "make file"),
                weight = 0.9,
                requiredParams = mapOf("action" to "write"),
            ),
            CapabilityRule(
                id = CapabilityIds.FILES_SEARCH,
                patterns = listOf("find file", "search file", "locate file"),
                weight = 0.9,
                requiredParams = mapOf("action" to "search"),
            ),

            // Media
            CapabilityRule(
                id = CapabilityIds.MEDIA_CONTROLS,
                patterns = listOf("volume", "mute", "unmute", "play", "pause", "next track", "previous track"),
                weight = 0.9,
                requiredParams = mapOf("action" to "volume_up"), // default, will be overridden
            ),

            // Web
            CapabilityRule(
                id = CapabilityIds.WEB_SEARCH,
                patterns = listOf("search web", "google ", "search for", "look up", "find online"),
                weight = 1.0,
                requiredParams = mapOf("action" to "search"),
            ),
            CapabilityRule(
                id = CapabilityIds.WEB_FETCH,
                patterns = listOf("fetch ", "read url", "open url", "visit "),
                weight = 0.8,
                requiredParams = mapOf("action" to "fetch"),
            ),

            // System
            CapabilityRule(
                id = CapabilityIds.SYSTEM_SETTINGS,
                patterns = listOf("settings", "open settings"),
                weight = 0.9,
                requiredParams = mapOf("action" to "open_settings"),
            ),
            CapabilityRule(
                id = CapabilityIds.SYSTEM_INTENTS,
                patterns = listOf("alarm", "set alarm"),
                weight = 0.9,
                requiredParams = mapOf("action" to "set_alarm"),
            ),
            CapabilityRule(
                id = CapabilityIds.SYSTEM_INTENTS,
                patterns = listOf("timer", "set timer"),
                weight = 0.9,
                requiredParams = mapOf("action" to "set_timer"),
            ),
        )

        // Match against rules
        for (rule in rules) {
            val patternMatches = rule.patterns.count { lowerGoal.contains(it) }
            if (patternMatches > 0) {
                val capability = capabilities[rule.id]
                if (capability != null) {
                    val isAvailable = capability.isAvailable()
                    if (isAvailable) {
                        // Extract parameters from goal using rule's requiredParams as base
                        val params = mutableMapOf<String, Any>()
                        params.putAll(rule.requiredParams)
                        // Try to extract additional params from the goal
                        extractParamsFromGoal(lowerGoal, rule.id, params)

                        matches.add(CapabilityMatch(
                            capability = capability,
                            confidence = rule.weight * minOf(patternMatches, 3) / 3.0,
                            extractedParams = params.toMap(),
                        ))
                    }
                }
            }
        }

        // Sort by confidence descending
        return matches.sortedByDescending { it.confidence }
    }

    private fun extractParamsFromGoal(goal: String, capabilityId: String, params: MutableMap<String, Any>) {
        // Extract parameters from natural language based on capability type
        when (capabilityId) {
            CapabilityIds.MESSAGES -> {
                // Extract phone number and message
                val phoneRegex = Regex("""(\+?\d[\d\s\-\(\)]{7,})""")
                val match = phoneRegex.find(goal)
                match?.let { params["to"] = it.value.replace(Regex("""[\s\-\(\)]"""), "") }

                // Extract message content after "saying" or "that"
                val msgMatch = Regex("""(?:saying|that|message|text)\s+["']?([^"']+)["']?""").find(goal)
                msgMatch?.let { params["body"] = it.groupValues[1] }
            }
            CapabilityIds.PHONE_CALLS -> {
                val phoneRegex = Regex("""(\+?\d[\d\s\-\(\)]{7,})""")
                val match = phoneRegex.find(goal)
                match?.let { params["number"] = it.value.replace(Regex("""[\s\-\(\)]"""), "") }
            }
            CapabilityIds.CONTACTS -> {
                // Extract name after "contact" or "find"
                val nameMatch = Regex("""(?:contact|find|lookup)\s+(\w+(?:\s+\w+)*)""").find(goal)
                nameMatch?.let { params["name"] = it.groupValues[1] }
            }
            CapabilityIds.CALENDAR_EVENTS -> {
                // Try to extract time references
                val timeMatch = Regex("""(?:at|for)\s+(\d{1,2}):(\d{2})\s*(am|pm)?""").find(goal)
                timeMatch?.let {
                    val hour = it.groupValues[1].toInt()
                    val minute = it.groupValues[2].toInt()
                    val ampm = it.groupValues[3]
                    var finalHour = hour
                    if (ampm == "pm" && hour < 12) finalHour += 12
                    if (ampm == "am" && hour == 12) finalHour = 0
                    val now = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = now
                    cal.set(java.util.Calendar.HOUR_OF_DAY, finalHour)
                    cal.set(java.util.Calendar.MINUTE, minute)
                    cal.set(java.util.Calendar.SECOND, 0)
                    if (cal.timeInMillis < now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                    params["start_time"] = cal.timeInMillis
                    params["end_time"] = cal.timeInMillis + 3600000 // 1 hour default
                }
            }
            CapabilityIds.LAUNCH_APP -> {
                val appMatch = Regex("""(?:open|launch|start)\s+(\w+(?:\s+\w+)*)""").find(goal)
                appMatch?.let { params["target"] = it.groupValues[1] }
            }
            CapabilityIds.WEB_SEARCH -> {
                val queryMatch = Regex("""(?:search|find|look up)\s+(?:for\s+)?(.+)""").find(goal)
                queryMatch?.let { params["query"] = it.groupValues[1].trim() }
            }
            CapabilityIds.WEB_FETCH -> {
                val urlMatch = Regex("""(?:https?://[^\s]+)""").find(goal)
                urlMatch?.let { params["url"] = it.value }
            }
        }
    }

    /**
     * Get a human-readable summary of all capabilities
     */
    suspend fun getCapabilitiesSummary(): String {
        val allCaps = getAllCapabilities()
        val grouped = allCaps.groupBy { it.id.substringBefore('.') }
        return grouped.entries.joinToString("\n") { (category, caps) ->
            "$category:\n${caps.joinToString("\n") { "  - ${it.description}" }}"
        }
    }
}

data class CapabilityMatch(
    val capability: Capability,
    val confidence: Double,
    val extractedParams: Map<String, Any>,
)

data class CapabilityRule(
    val id: String,
    val patterns: List<String>,
    val weight: Double,
    val requiredParams: Map<String, Any>,
)