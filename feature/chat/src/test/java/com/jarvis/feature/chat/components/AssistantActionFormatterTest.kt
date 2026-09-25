package com.jarvis.feature.chat.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AssistantActionFormatterTest {

    @Test
    fun `maps tool names to human-readable progress descriptions`() {
        assertEquals("Searching the web", AssistantActionFormatter.toProgressDescription("web_search"))
        assertEquals("Reading web article", AssistantActionFormatter.toProgressDescription("fetch_url"))
        assertEquals("Preparing SMS", AssistantActionFormatter.toProgressDescription("send_sms"))
        assertEquals("Checking device status", AssistantActionFormatter.toProgressDescription("get_device_telemetry"))
        assertEquals("Checking your calendar", AssistantActionFormatter.toProgressDescription("query_calendar"))
        assertEquals("Looking up contacts", AssistantActionFormatter.toProgressDescription("query_contacts"))
        assertEquals("Setting alarm/timer", AssistantActionFormatter.toProgressDescription("set_alarm"))
        assertEquals("Custom Action", AssistantActionFormatter.toProgressDescription("custom_action"))
    }

    @Test
    fun `meaningful state-changing actions produce milestones`() {
        assertEquals("Flashlight turned on", AssistantActionFormatter.toCompletedDescription("set_flashlight", "Flashlight turned on."))
        assertEquals("Flashlight turned off", AssistantActionFormatter.toCompletedDescription("set_flashlight", "Flashlight turned off."))
        assertEquals("Message sent", AssistantActionFormatter.toCompletedDescription("send_sms"))
        assertEquals("Call started", AssistantActionFormatter.toCompletedDescription("place_call"))
        assertEquals("Reminder created", AssistantActionFormatter.toCompletedDescription("create_task"))
        assertEquals("Reminder created", AssistantActionFormatter.toCompletedDescription("set_reminder"))
        assertEquals("Alarm set", AssistantActionFormatter.toCompletedDescription("set_alarm"))
        assertEquals("Timer started", AssistantActionFormatter.toCompletedDescription("set_timer"))
        assertEquals("Event scheduled", AssistantActionFormatter.toCompletedDescription("create_calendar_event"))
        assertEquals("App opened", AssistantActionFormatter.toCompletedDescription("launch_app"))
        assertEquals("Play Store opened to install", AssistantActionFormatter.toCompletedDescription("install_app"))
        assertEquals("Volume adjusted", AssistantActionFormatter.toCompletedDescription("set_volume"))
        assertEquals("File created", AssistantActionFormatter.toCompletedDescription("create_file"))
        assertEquals("File deleted", AssistantActionFormatter.toCompletedDescription("delete_file"))
        assertEquals("Memory saved", AssistantActionFormatter.toCompletedDescription("store_memory"))
        assertEquals("Action undone", AssistantActionFormatter.toCompletedDescription("undo_action"))
    }

    @Test
    fun `internal reads queries and calculations produce no milestone`() {
        assertNull(AssistantActionFormatter.toCompletedDescription("web_search"))
        assertNull(AssistantActionFormatter.toCompletedDescription("fetch_url"))
        assertNull(AssistantActionFormatter.toCompletedDescription("get_device_telemetry"))
        assertNull(AssistantActionFormatter.toCompletedDescription("get_battery_status"))
        assertNull(AssistantActionFormatter.toCompletedDescription("current_time"))
        assertNull(AssistantActionFormatter.toCompletedDescription("get_current_datetime"))
        assertNull(AssistantActionFormatter.toCompletedDescription("query_calendar"))
        assertNull(AssistantActionFormatter.toCompletedDescription("query_contacts"))
        assertNull(AssistantActionFormatter.toCompletedDescription("query_tasks"))
        assertNull(AssistantActionFormatter.toCompletedDescription("read_file"))
        assertNull(AssistantActionFormatter.toCompletedDescription("search_files"))
        assertNull(AssistantActionFormatter.toCompletedDescription("calculator"))
        assertNull(AssistantActionFormatter.toCompletedDescription("retrieve_memories"))
        assertNull(AssistantActionFormatter.toCompletedDescription("custom_internal_tool"))
    }

    @Test
    fun `maps tool names to human-readable titles`() {
        assertEquals("Web Search", AssistantActionFormatter.toHumanReadableTitle("web_search"))
        assertEquals("Send SMS Message", AssistantActionFormatter.toHumanReadableTitle("send_sms"))
        assertEquals("Schedule Calendar Event", AssistantActionFormatter.toHumanReadableTitle("create_calendar_event"))
        assertEquals("Contact Lookup", AssistantActionFormatter.toHumanReadableTitle("query_contacts"))
    }
}
