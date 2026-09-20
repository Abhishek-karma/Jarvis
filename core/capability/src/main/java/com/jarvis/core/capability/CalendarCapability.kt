package com.jarvis.core.capability

import android.content.Context
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.core.database.repository.ReversibleActionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calendar capabilities - events, reminders, schedules
 */
class CalendarCapability(
    private val context: Context,
    private val reversibleActionRepository: ReversibleActionRepository? = null,
) : Capability {

    override val id = CapabilityIds.CALENDAR_EVENTS

    override val description = "Manage calendar events, reminders, and schedules"

    override val requiredPermissions = listOf(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver != null
    }

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: create_event, query_events, update_event, delete_event, create_reminder",
        )

        return when (action) {
            "create_event" -> createEvent(request)
            "query_events" -> queryEvents(request)
            "update_event" -> updateEvent(request)
            "delete_event" -> deleteEvent(request)
            "create_reminder" -> createReminder(request)
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown calendar action: $action",
            )
        }
    }

    private suspend fun createEvent(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val title = request.parameters["title"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'title'"
        )
        val startTime = request.parameters["start_time"] as? Long ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'start_time' (millis UTC)"
        )
        val endTime = request.parameters["end_time"] as? Long ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'end_time' (millis UTC)"
        )
        val description = request.parameters["description"] as? String
        val location = request.parameters["location"] as? String

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Calendar write permission not granted",
                missingPermissions = listOf(android.Manifest.permission.WRITE_CALENDAR),
            )
        }

        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
                put(android.provider.CalendarContract.Events.DTSTART, startTime)
                put(android.provider.CalendarContract.Events.DTEND, endTime)
                put(android.provider.CalendarContract.Events.CALENDAR_ID, primaryCalendarId() ?: 1L)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
            val uri = context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                ?: return@withContext CapabilityResult.Failure("CALENDAR_ERROR", "Calendar provider refused the insert")
            val id = android.content.ContentUris.parseId(uri)

            reversibleActionRepository?.recordAction(
                com.jarvis.core.common.ReversibleAction(
                    actionType = "calendar_event",
                    target = id.toString(),
                    inverseActionJson = "{\"action\":\"delete_event\",\"eventId\":$id}",
                )
            )

            CapabilityResult.Success(
                output = "Calendar event created: $title",
                structuredData = mapOf("event_id" to id, "title" to title, "start" to startTime, "end" to endTime),
                verificationHint = VerificationHint("calendar.event_created", mapOf("event_id" to id)),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("CALENDAR_ERROR", "Failed to create event: ${e.message}")
        }
    }

    private suspend fun queryEvents(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val from = request.parameters["from"] as? Long ?: System.currentTimeMillis()
        val to = request.parameters["to"] as? Long ?: (System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000)

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Calendar read permission not granted",
                missingPermissions = listOf(android.Manifest.permission.READ_CALENDAR),
            )
        }

        try {
            val projection = arrayOf(
                android.provider.CalendarContract.Events._ID,
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND,
                android.provider.CalendarContract.Events.EVENT_LOCATION,
            )
            val selection = "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?"
            val events = context.contentResolver
                .query(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(from.toString(), to.toString()),
                    "${android.provider.CalendarContract.Events.DTSTART} ASC",
                )
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                mapOf<String, Any>(
                                    "id" to cursor.getLong(0),
                                    "title" to (cursor.getString(1) ?: "(untitled)"),
                                    "start" to cursor.getLong(2),
                                    "end" to cursor.getLong(3),
                                    "location" to cursor.getString(4),
                                )
                            )
                        }
                    }
                } ?: emptyList()

            CapabilityResult.Success(
                output = if (events.isNotEmpty()) {
                    "Found ${events.size} event(s)"
                } else {
                    "No events in the selected range"
                },
                structuredData = mapOf("events" to events),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("CALENDAR_ERROR", "Failed to query events: ${e.message}")
        }
    }

    private suspend fun updateEvent(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val eventId = request.parameters["event_id"] as? Long ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'event_id'"
        )

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Calendar write permission not granted",
                missingPermissions = listOf(android.Manifest.permission.WRITE_CALENDAR),
            )
        }

        try {
            val values = android.content.ContentValues().apply {
                val title = request.parameters["title"] as? String
                val description = request.parameters["description"] as? String
                val location = request.parameters["location"] as? String
                val startTime = request.parameters["start_time"] as? Long
                val endTime = request.parameters["end_time"] as? Long
                title?.let { put(android.provider.CalendarContract.Events.TITLE, it) }
                description?.let { put(android.provider.CalendarContract.Events.DESCRIPTION, it) }
                location?.let { put(android.provider.CalendarContract.Events.EVENT_LOCATION, it) }
                startTime?.let { put(android.provider.CalendarContract.Events.DTSTART, it) }
                endTime?.let { put(android.provider.CalendarContract.Events.DTEND, it) }
            }
            val uri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, eventId)
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated == 0) return@withContext CapabilityResult.Failure("NOT_FOUND", "Event $eventId not found")

            CapabilityResult.Success(
                output = "Calendar event $eventId updated",
                structuredData = mapOf("event_id" to eventId),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("CALENDAR_ERROR", "Failed to update event: ${e.message}")
        }
    }

    private suspend fun deleteEvent(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val eventId = request.parameters["event_id"] as? Long ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'event_id'"
        )

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Calendar write permission not granted",
                missingPermissions = listOf(android.Manifest.permission.WRITE_CALENDAR),
            )
        }

        try {
            val uri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, eventId)
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted == 0) return@withContext CapabilityResult.Failure("NOT_FOUND", "Event $eventId not found")

            CapabilityResult.Success(
                output = "Calendar event $eventId deleted",
                structuredData = mapOf("event_id" to eventId),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("CALENDAR_ERROR", "Failed to delete event: ${e.message}")
        }
    }

    private suspend fun createReminder(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val title = request.parameters["title"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'title'"
        )
        val remindAt = request.parameters["remind_at"] as? Long ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'remind_at' (millis UTC)"
        )

        return@withContext createEvent(
            CapabilityRequest(
                goalDescription = request.goalDescription,
                parameters = mapOf<String, Any>(
                    "action" to "create_event",
                    "title" to title,
                    "start_time" to remindAt,
                    "end_time" to remindAt,
                    "description" to "Reminder set by Jarvis",
                ),
            ),
        )
    }

    private fun primaryCalendarId(): Long? {
        val uri = android.provider.CalendarContract.Calendars.CONTENT_URI
        return context.contentResolver
            .query(uri, arrayOf(android.provider.CalendarContract.Calendars._ID), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }
}