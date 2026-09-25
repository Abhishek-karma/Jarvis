package com.jarvis.feature.chat.components

/**
 * Maps technical tool names to natural, friendly assistant action labels.
 * Only meaningful state-changing user-visible actions produce milestone labels.
 */
object AssistantActionFormatter {

    fun toProgressDescription(toolName: String): String = when (toolName.lowercase()) {
        "web_search", "search_web" -> "Searching the web"
        "fetch_url" -> "Reading web article"
        "send_sms", "send_message" -> "Preparing SMS"
        "place_call", "call" -> "Starting call"
        "get_device_telemetry", "get_battery_status" -> "Checking device status"
        "get_current_datetime" -> "Checking current date & time"
        "launch_app" -> "Opening application"
        "install_app" -> "Opening Play Store to install"
        "calculator" -> "Calculating"
        "create_file" -> "Creating file"
        "read_file" -> "Reading file"
        "delete_file" -> "Deleting file"
        "search_files", "list_files" -> "Searching files"
        "query_calendar", "create_calendar_event" -> "Checking your calendar"
        "query_contacts" -> "Looking up contacts"
        "set_alarm", "set_timer" -> "Setting alarm/timer"
        "query_device_volume", "set_volume" -> "Adjusting audio"
        "set_flashlight" -> "Controlling flashlight"
        "query_installed_apps" -> "Checking installed apps"
        "create_task", "query_tasks" -> "Updating your tasks"
        "store_memory", "retrieve_memories" -> "Updating preferences & memory"
        "send_app_message" -> "Preparing app message"
        "search_in_app" -> "Searching in app"
        "open_settings" -> "Opening settings"
        "ui_click" -> "Interacting with screen"
        "ui_type" -> "Typing text"
        "ui_scroll" -> "Scrolling screen"
        "ui_go_back" -> "Navigating back"
        "ui_verify" -> "Verifying screen"
        "ui_inspect" -> "Inspecting screen"
        else -> toolName.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /**
     * Maps user-visible, meaningful state-changing actions to lightweight milestone labels.
     * Returns null for read-only lookups, queries, calculations, and internal tools so
     * they do not produce milestone noise in the chat timeline.
     */
    fun toCompletedDescription(toolName: String, observation: String? = null): String? {
        val lower = toolName.lowercase().trim()
        val obsLower = observation?.lowercase().orEmpty()
        return when (lower) {
            "set_flashlight", "flashlight", "torch" -> {
                if (obsLower.contains("off") || obsLower.contains("disabled") || obsLower.contains("false")) {
                    "Flashlight turned off"
                } else {
                    "Flashlight turned on"
                }
            }
            "create_task", "add_task", "set_reminder", "create_reminder" -> "Reminder created"
            "create_calendar_event", "add_calendar_event" -> "Event scheduled"
            "set_alarm", "create_alarm" -> "Alarm set"
            "set_timer", "start_timer" -> "Timer started"
            "send_sms", "send_message", "send_app_message" -> "Message sent"
            "search_in_app" -> "Search completed"
            "place_call", "call", "start_call" -> "Call started"
            "launch_app", "open_app" -> "App opened"
            "install_app" -> "Play Store opened to install"
            "open_settings" -> "Settings opened"
            "set_volume", "adjust_volume" -> "Volume adjusted"
            "create_file", "write_file" -> "File created"
            "delete_file", "remove_file" -> "File deleted"
            "store_memory", "save_memory" -> "Memory saved"
            "undo_action" -> "Action undone"
            // All lookup, query, calculation, and intermediate tools return null (NO MILESTONE)
            else -> null
        }
    }

    fun toHumanReadableTitle(toolName: String): String = when (toolName.lowercase()) {
        "web_search", "search_web" -> "Web Search"
        "fetch_url" -> "Read Web Article"
        "send_sms", "send_message" -> "Send SMS Message"
        "place_call", "call" -> "Place Phone Call"
        "get_device_telemetry" -> "Device Telemetry"
        "launch_app" -> "Launch App"
        "install_app" -> "Install App"
        "calculator" -> "Calculator"
        "create_file" -> "Create File"
        "read_file" -> "Read File"
        "delete_file" -> "Delete File"
        "search_files" -> "Search Files"
        "query_calendar" -> "Calendar Lookup"
        "create_calendar_event" -> "Schedule Calendar Event"
        "query_contacts" -> "Contact Lookup"
        "set_alarm" -> "Set Alarm"
        "set_timer" -> "Start Timer"
        "set_flashlight" -> "Control Flashlight"
        "set_volume" -> "Adjust Volume"
        "create_task" -> "Add Task"
        else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
