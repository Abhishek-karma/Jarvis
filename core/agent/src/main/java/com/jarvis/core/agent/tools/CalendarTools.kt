package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import java.time.Instant

/**
 * Calendar tools (v0.5 catalog, `06-AGENT §3`): create events, list upcoming events, and
 * set reminders. Android's CalendarContract is injected as plain lambdas (following the
 * [SystemInfoTools] pattern) so every tool is JVM-unit-testable; AgentModule binds the
 * real ContentResolver readers/writers behind them.
 */
object CalendarTools {
    const val CREATE_EVENT = "create_event"
    const val LIST_EVENTS = "list_events"
    const val SET_REMINDER = "set_reminder"

    val manifestNames: List<String> = listOf(CREATE_EVENT, LIST_EVENTS, SET_REMINDER)

    /** All calendar tools, wired to the platform gateways the host app provides. */
    fun all(
        insertEvent: suspend (CalendarEventDraft) -> Result<Long>,
        queryEvents: suspend (fromUtcMillis: Long, toUtcMillis: Long) -> Result<List<CalendarEvent>>,
        insertReminder: suspend (ReminderDraft) -> Result<Long>,
    ): List<Tool> =
        listOf(
            createEvent(insertEvent),
            listEvents(queryEvents),
            setReminder(insertReminder),
        )

    /** Values for one calendar row to insert. Times are UTC epoch millis. */
    data class CalendarEventDraft(
        val title: String,
        val description: String?,
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val location: String?,
    )

    /** One calendar row as read back from the provider. */
    data class CalendarEvent(
        val eventId: Long,
        val title: String,
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val location: String?,
    )

    /** Values for one reminder (a zero-duration event with an alarm offset). */
    data class ReminderDraft(
        val title: String,
        val remindAtUtcMillis: Long,
    )

    fun createEvent(insert: suspend (CalendarEventDraft) -> Result<Long>): Tool =
        object : Tool {
            override val name = CREATE_EVENT
            override val description =
                "Create a calendar event with a title, start and end time (UTC epoch millis), " +
                    "and optional description and location. Sensitive: writes to the user's calendar."
            override val tier = PermissionTier.SENSITIVE
            override val parametersSchemaJson = CREATE_EVENT_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson) ?: return parseFailure()
                val title = args.string("title")
                val start = args.long("start_utc_millis")
                val end = args.long("end_utc_millis")
                if (title == null || start == null || end == null) {
                    return missing("title, start_utc_millis and end_utc_millis are required")
                }
                if (end < start) {
                    return ToolResult(
                        success = false,
                        observationText = "Event end time is before its start time.",
                        error = "end_utc_millis < start_utc_millis",
                    )
                }
                return insert(
                    CalendarEventDraft(
                        title = title,
                        description = args.string("description"),
                        startUtcMillis = start,
                        endUtcMillis = end,
                        location = args.string("location"),
                    ),
                ).fold(
                    onSuccess = { id ->
                        ToolResult(
                            success = true,
                            observationText = "Event \"$title\" created (${formatUtc(start)} – ${formatUtc(end)}).",
                            structuredData = mapOf("eventId" to id),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not create the event.",
                            error = error.message ?: "Calendar insert failed",
                        )
                    },
                )
            }
        }

    fun listEvents(query: suspend (Long, Long) -> Result<List<CalendarEvent>>): Tool =
        object : Tool {
            override val name = LIST_EVENTS
            override val description =
                "List calendar events between two times (UTC epoch millis, max 14 days apart). Read-only."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = LIST_EVENTS_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson) ?: return parseFailure()
                val from = args.long("from_utc_millis")
                val to = args.long("to_utc_millis")
                if (from == null || to == null) {
                    return missing("from_utc_millis and to_utc_millis are required")
                }
                if (to < from) {
                    return ToolResult(
                        success = false,
                        observationText = "Query range end is before its start.",
                        error = "to_utc_millis < from_utc_millis",
                    )
                }
                if (to - from > MAX_RANGE_MILLIS) {
                    return ToolResult(
                        success = false,
                        observationText = "Query range exceeds 14 days; narrow it.",
                        error = "range too wide",
                    )
                }
                return query(from, to).fold(
                    onSuccess = { events ->
                        if (events.isEmpty()) {
                            ToolResult(
                                success = true,
                                observationText = "No events between ${formatUtc(from)} and ${formatUtc(to)}.",
                                structuredData = mapOf("count" to 0),
                            )
                        } else {
                            val lines =
                                events.joinToString("\n") { event ->
                                    "- \"${event.title}\" ${formatUtc(event.startUtcMillis)}" +
                                        (event.location?.let { " @ $it" } ?: "")
                                }
                            ToolResult(
                                success = true,
                                observationText = "${events.size} event(s):\n$lines",
                                structuredData = mapOf("count" to events.size),
                            )
                        }
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not read the calendar.",
                            error = error.message ?: "Calendar query failed",
                        )
                    },
                )
            }
        }

    fun setReminder(insert: suspend (ReminderDraft) -> Result<Long>): Tool =
        object : Tool {
            override val name = SET_REMINDER
            override val description =
                "Set a reminder that fires at the given time (UTC epoch millis). Reversible write: the " +
                    "user can dismiss or delete the resulting alarm."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = SET_REMINDER_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson) ?: return parseFailure()
                val title = args.string("title")
                val remindAt = args.long("remind_at_utc_millis")
                if (title == null || remindAt == null) {
                    return missing("title and remind_at_utc_millis are required")
                }
                if (remindAt < System.currentTimeMillis() - STALE_GRACE_MILLIS) {
                    return ToolResult(
                        success = false,
                        observationText = "Reminder time is in the past.",
                        error = "remind_at_utc_millis is in the past",
                    )
                }
                return insert(ReminderDraft(title, remindAt)).fold(
                    onSuccess = { id ->
                        ToolResult(
                            success = true,
                            observationText = "Reminder \"$title\" set for ${formatUtc(remindAt)}.",
                            structuredData = mapOf("reminderId" to id),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not set the reminder.",
                            error = error.message ?: "Reminder insert failed",
                        )
                    },
                )
            }
        }

    // ---- helpers ----

    internal const val MAX_RANGE_MILLIS = 14L * 24 * 60 * 60 * 1000
    internal const val STALE_GRACE_MILLIS = 60_000L // tolerate minor clock skew

    private fun parseFailure() =
        ToolResult(
            success = false,
            observationText = "Arguments are not valid JSON.",
            error = "invalid JSON arguments",
        )

    private fun missing(what: String) =
        ToolResult(
            success = false,
            observationText = "Missing argument: $what.",
            error = what,
        )

    private fun formatUtc(millis: Long) = Instant.ofEpochMilli(millis).toString()

    private const val CREATE_EVENT_SCHEMA =
        """{"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},"start_utc_millis":{"type":"integer"},"end_utc_millis":{"type":"integer"},"location":{"type":"string"}},"required":["title","start_utc_millis","end_utc_millis"]}"""
    private const val LIST_EVENTS_SCHEMA =
        """{"type":"object","properties":{"from_utc_millis":{"type":"integer"},"to_utc_millis":{"type":"integer"}},"required":["from_utc_millis","to_utc_millis"]}"""
    private const val SET_REMINDER_SCHEMA =
        """{"type":"object","properties":{"title":{"type":"string"},"remind_at_utc_millis":{"type":"integer"}},"required":["title","remind_at_utc_millis"]}"""
}
