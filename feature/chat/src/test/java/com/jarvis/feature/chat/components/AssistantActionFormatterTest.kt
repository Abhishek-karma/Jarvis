package com.jarvis.feature.chat.components

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `maps tool names to completion descriptions`() {
        assertEquals("Web search completed", AssistantActionFormatter.toCompletedDescription("web_search"))
        assertEquals("Web page read", AssistantActionFormatter.toCompletedDescription("fetch_url"))
        assertEquals("SMS sent", AssistantActionFormatter.toCompletedDescription("send_sms"))
        assertEquals("Calendar checked", AssistantActionFormatter.toCompletedDescription("query_calendar"))
        assertEquals("Contact found", AssistantActionFormatter.toCompletedDescription("query_contacts"))
        assertEquals("Alarm set", AssistantActionFormatter.toCompletedDescription("set_alarm"))
        assertEquals("Custom action done", AssistantActionFormatter.toCompletedDescription("custom_action"))
    }

    @Test
    fun `maps tool names to human-readable titles`() {
        assertEquals("Web Search", AssistantActionFormatter.toHumanReadableTitle("web_search"))
        assertEquals("Send SMS Message", AssistantActionFormatter.toHumanReadableTitle("send_sms"))
        assertEquals("Schedule Calendar Event", AssistantActionFormatter.toHumanReadableTitle("create_calendar_event"))
        assertEquals("Contact Lookup", AssistantActionFormatter.toHumanReadableTitle("query_contacts"))
    }
}
