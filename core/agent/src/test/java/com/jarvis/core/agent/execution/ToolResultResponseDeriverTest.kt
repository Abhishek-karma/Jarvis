package com.jarvis.core.agent.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolResultResponseDeriverTest {

    @Test
    fun `isGenericFallback identifies forbidden generic success phrases`() {
        // Forbidden fallbacks specified in requirements
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Task completed."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Task completed"))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Done."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Done"))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Completed current_time."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Completed play_music."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Completed list_events."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Routine executed successfully."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Routine executed successfully"))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Operation completed successfully."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("Action completed."))
        assertTrue(ToolResultResponseDeriver.isGenericFallback(""))
        assertTrue(ToolResultResponseDeriver.isGenericFallback("   "))
        assertTrue(ToolResultResponseDeriver.isGenericFallback(null))

        // Legitimate, informative assistant prose must NEVER be classified as generic fallback
        assertFalse(ToolResultResponseDeriver.isGenericFallback("You have 2 meetings on your calendar today: Standup at 9am."))
        assertFalse(ToolResultResponseDeriver.isGenericFallback("Now playing 'Bohemian Rhapsody' on Spotify."))
        assertFalse(ToolResultResponseDeriver.isGenericFallback("The flashlight has been turned on."))
        assertFalse(ToolResultResponseDeriver.isGenericFallback("Your battery is at 82%."))
    }

    @Test
    fun `calendar query explains the events and does not say Task completed`() {
        val calendarObservation = """
            [
              {"title":"Team Standup", "time":"9:00 AM", "location":"Room A"},
              {"title":"Lunch with Sarah", "time":"12:30 PM", "location":"Cafeteria"}
            ]
        """.trimIndent()

        val derived = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "list_events",
            observationText = calendarObservation,
            success = true,
        )

        assertFalse(derived.contains("Task completed"), "Derived calendar response must NOT say Task completed")
        assertFalse(derived.contains("Done"), "Derived calendar response must NOT say Done")
        assertTrue(derived.startsWith("Your calendar today has 2 event(s):"))
        assertTrue(derived.contains("Team Standup"), "Derived calendar response must mention Team Standup")
        assertTrue(derived.contains("Lunch with Sarah"), "Derived calendar response must mention Lunch with Sarah")
        assertTrue(derived.contains("9:00 AM") && derived.contains("12:30 PM"), "Derived calendar response must include times")
    }

    @Test
    fun `battery level check returns clear percentage statement`() {
        val derived = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "battery_level",
            observationText = "Battery at 68%.",
            success = true,
        )
        assertEquals("Your battery is at 68%.", derived)
    }

    @Test
    fun `play music when succeeded says what happened`() {
        val observation = "Playing 'Bohemian Rhapsody' on Spotify."
        val derived = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "play_media",
            observationText = observation,
            success = true,
            rawArgs = """{"query":"Bohemian Rhapsody","app_name":"spotify"}""",
        )

        assertEquals("Started playing 'Bohemian Rhapsody' on Spotify.", derived)
        assertFalse(derived.contains("Task completed"))
    }

    @Test
    fun `play music when failed explains the failure`() {
        val observation = "Could not find Spotify on this device."
        val derived = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "play_media",
            observationText = observation,
            success = false,
            rawArgs = """{"query":"Bohemian Rhapsody"}""",
        )

        assertTrue(derived.startsWith("I couldn't play music because"))
        assertTrue(derived.contains("Spotify"))
        assertFalse(derived.contains("Task completed"))
    }

    @Test
    fun `play music when result is uncertain states outcome cannot be confirmed`() {
        val observation = "Playback intent was broadcast, but playback state is uncertain."
        val derived = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "play_media",
            observationText = observation,
            success = true,
        )

        assertEquals("I started the operation, but I can't confirm whether it completed.", derived)
        assertFalse(derived.contains("Task completed"))
    }

    @Test
    fun `uncertain error code maps to uncertain explanation`() {
        val derived = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "transfer_funds",
            observationText = "",
            success = false,
            errorCode = ErrorCode.OPERATION_UNCERTAIN,
        )

        assertEquals("I started the operation, but I can't confirm whether it completed.", derived)
        assertFalse(derived.contains("Task completed"))
    }

    @Test
    fun `sanitizes internal architecture concepts from user response`() {
        val input = "AgentRunner failed: ToolExecutor encountered TaskEngine error in OperationRepository during idempotency check on execution state."
        val sanitized = ToolResultResponseDeriver.sanitizeArchitectureConcepts(input)

        assertFalse(sanitized.contains("AgentRunner", ignoreCase = true))
        assertFalse(sanitized.contains("ToolExecutor", ignoreCase = true))
        assertFalse(sanitized.contains("TaskEngine", ignoreCase = true))
        assertFalse(sanitized.contains("OperationRepository", ignoreCase = true))
        assertFalse(sanitized.contains("idempotency", ignoreCase = true))
        assertFalse(sanitized.contains("execution state", ignoreCase = true))
    }

    @Test
    fun `empty final turn with low-information ok observation describes what happened truthfully`() {
        val flashlightOn = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "set_flashlight",
            observationText = "ok",
            success = true,
            rawArgs = """{"enabled":true}""",
        )
        assertEquals("Flashlight turned on.", flashlightOn)

        val flashlightOff = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "set_flashlight",
            observationText = "ok",
            success = true,
            rawArgs = """{"enabled":false}""",
        )
        assertEquals("Flashlight turned off.", flashlightOff)

        val currentTime = ToolResultResponseDeriver.deriveConciseResponse(
            toolName = "current_time",
            observationText = "ok",
            success = true,
        )
        assertEquals("Action 'current_time' completed with result: ok.", currentTime)
        assertFalse(currentTime.contains("Task completed"))
        assertFalse(currentTime.contains("Done"))
    }
}
