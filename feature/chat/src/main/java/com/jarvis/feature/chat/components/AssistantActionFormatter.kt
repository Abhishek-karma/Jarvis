package com.jarvis.feature.chat.components

/**
 * Maps technical tool names to natural, friendly assistant action labels.
 */
object AssistantActionFormatter {

    fun toProgressDescription(toolName: String): String = when (toolName.lowercase()) {
        "web_search", "search_web" -> "Searching the web"
        "fetch_url" -> "Reading web article"
        "send_sms", "send_message" -> "Preparing SMS"
        "get_device_telemetry", "get_battery_status" -> "Checking device status"
        "get_current_datetime" -> "Checking current date & time"
        "launch_app" -> "Opening application"
        "calculator" -> "Calculating"
        "create_file" -> "Creating file"
        "read_file" -> "Reading file"
        "search_files", "list_files" -> "Searching files"
        "query_calendar", "create_calendar_event" -> "Checking your calendar"
        "query_contacts" -> "Looking up contacts"
        "set_alarm", "set_timer" -> "Setting alarm/timer"
        "query_device_volume", "set_volume" -> "Adjusting audio"
        "query_installed_apps" -> "Checking installed apps"
        "create_task", "query_tasks" -> "Updating your tasks"
        "store_memory", "retrieve_memories" -> "Updating preferences & memory"
        else -> toolName.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    fun toCompletedDescription(toolName: String): String = when (toolName.lowercase()) {
        "web_search", "search_web" -> "Web search completed"
        "fetch_url" -> "Web page read"
        "send_sms", "send_message" -> "SMS sent"
        "get_device_telemetry", "get_battery_status" -> "Device status checked"
        "get_current_datetime" -> "Date & time verified"
        "launch_app" -> "App launched"
        "calculator" -> "Calculation completed"
        "create_file" -> "File created"
        "read_file" -> "File read"
        "search_files", "list_files" -> "Files searched"
        "query_calendar" -> "Calendar checked"
        "create_calendar_event" -> "Calendar event scheduled"
        "query_contacts" -> "Contact found"
        "set_alarm" -> "Alarm set"
        "set_timer" -> "Timer started"
        "set_volume" -> "Volume adjusted"
        "create_task" -> "Task recorded"
        "store_memory" -> "Memory saved"
        else -> "${toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }} done"
    }

    fun toHumanReadableTitle(toolName: String): String = when (toolName.lowercase()) {
        "web_search", "search_web" -> "Web Search"
        "fetch_url" -> "Read Web Article"
        "send_sms", "send_message" -> "Send SMS Message"
        "get_device_telemetry" -> "Device Telemetry"
        "launch_app" -> "Launch App"
        "calculator" -> "Calculator"
        "create_file" -> "Create File"
        "read_file" -> "Read File"
        "search_files" -> "Search Files"
        "query_calendar" -> "Calendar Lookup"
        "create_calendar_event" -> "Schedule Calendar Event"
        "query_contacts" -> "Contact Lookup"
        "set_alarm" -> "Set Alarm"
        "set_timer" -> "Start Timer"
        "create_task" -> "Add Task"
        else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
