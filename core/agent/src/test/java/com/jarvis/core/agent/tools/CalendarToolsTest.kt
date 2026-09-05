package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** JVM tests for the calendar tools — platform gateways are plain lambdas. */
class CalendarToolsTest {
    private val dayStart = 1_800_000_000_000L // fixed epoch reference

    @Test
    fun `tiers are declared per spec`() {
        assertEquals(PermissionTier.SENSITIVE, CalendarTools.createEvent { Result.success(1L) }.tier)
        assertEquals(PermissionTier.READ_ONLY, CalendarTools.listEvents { _, _ -> Result.success(emptyList()) }.tier)
        assertEquals(PermissionTier.REVERSIBLE_WRITE, CalendarTools.setReminder { Result.success(1L) }.tier)
    }

    @Test
    fun `create_event inserts and reports the id`() =
        runBlocking {
            val inserted = mutableListOf<CalendarTools.CalendarEventDraft>()
            val tool =
                CalendarTools.createEvent { draft ->
                    inserted += draft
                    Result.success(42L)
                }

            val result =
                tool.execute(
                    """{"title":"Dentist","start_utc_millis":$dayStart,"end_utc_millis":${dayStart + 3_600_000},"location":"Main St"}""",
                )

            assertTrue(result.success)
            assertTrue(result.observationText.contains("Dentist"))
            assertEquals(42L, result.structuredData?.get("eventId"))
            assertEquals("Dentist", inserted.single().title)
            assertEquals("Main St", inserted.single().location)
        }

    @Test
    fun `create_event rejects end before start`() =
        runBlocking {
            val tool = CalendarTools.createEvent { Result.success(1L) }

            val result =
                tool.execute(
                    """{"title":"X","start_utc_millis":$dayStart,"end_utc_millis":${dayStart - 1}}""",
                )

            assertFalse(result.success)
            assertTrue(result.error!!.contains("end_utc_millis"))
        }

    @Test
    fun `create_event reports platform failure`() =
        runBlocking {
            val tool = CalendarTools.createEvent { Result.failure(IllegalStateException("no calendar")) }

            val result = tool.execute("""{"title":"X","start_utc_millis":1,"end_utc_millis":2}""")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("no calendar"))
        }

    @Test
    fun `list_events formats the matching rows`() =
        runBlocking {
            val tool =
                CalendarTools.listEvents { from, to ->
                    assertEquals(dayStart, from)
                    assertEquals(dayStart + 86_400_000, to)
                    Result.success(
                        listOf(
                            CalendarTools.CalendarEvent(1L, "Standup", dayStart + 1, dayStart + 2, null),
                            CalendarTools.CalendarEvent(2L, "Lunch", dayStart + 10, dayStart + 20, "Cafe"),
                        ),
                    )
                }

            val result =
                tool.execute("""{"from_utc_millis":$dayStart,"to_utc_millis":${dayStart + 86_400_000}}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("2 event(s)"))
            assertTrue(result.observationText.contains("Standup"))
            assertTrue(result.observationText.contains("Cafe"))
        }

    @Test
    fun `list_events reports an empty window`() =
        runBlocking {
            val tool = CalendarTools.listEvents { _, _ -> Result.success(emptyList()) }

            val result = tool.execute("""{"from_utc_millis":1,"to_utc_millis":2}""")

            assertTrue(result.success)
            assertTrue(result.observationText.contains("No events"))
        }

    @Test
    fun `list_events rejects ranges wider than 14 days`() =
        runBlocking {
            val tool = CalendarTools.listEvents { _, _ -> Result.success(emptyList()) }

            val result =
                tool.execute(
                    """{"from_utc_millis":0,"to_utc_millis":${CalendarTools.MAX_RANGE_MILLIS + 1}}""",
                )

            assertFalse(result.success)
        }

    @Test
    fun `set_reminder inserts and reports the time`() =
        runBlocking {
            val tool = CalendarTools.setReminder { Result.success(7L) }

            val result =
                tool.execute(
                    """{"title":"Stretch","remind_at_utc_millis":${System.currentTimeMillis() + 60_000}}""",
                )

            assertTrue(result.success)
            assertTrue(result.observationText.contains("Stretch"))
            assertEquals(7L, result.structuredData?.get("reminderId"))
        }

    @Test
    fun `set_reminder rejects a past time`() =
        runBlocking {
            val tool = CalendarTools.setReminder { Result.success(1L) }

            val result =
                tool.execute(
                    """{"title":"Old","remind_at_utc_millis":${System.currentTimeMillis() - 600_000}}""",
                )

            assertFalse(result.success)
        }

    @Test
    fun `missing required arguments fail cleanly`() =
        runBlocking {
            val tool = CalendarTools.createEvent { Result.success(1L) }

            val result = tool.execute("""{"title":"No times"}""")

            assertFalse(result.success)
            assertTrue(result.observationText.contains("required"))
        }

    @Test
    fun `malformed JSON fails cleanly`() =
        runBlocking {
            val tool = CalendarTools.createEvent { Result.success(1L) }

            val result = tool.execute("not json")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("JSON"))
        }
}
