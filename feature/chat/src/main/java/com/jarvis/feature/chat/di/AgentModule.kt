package com.jarvis.feature.chat.di

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.tools.AlarmTools
import com.jarvis.core.agent.tools.CalendarTools
import com.jarvis.core.agent.tools.CalendarTools.CalendarEvent
import com.jarvis.core.agent.tools.CalendarTools.CalendarEventDraft
import com.jarvis.core.agent.tools.CalendarTools.ReminderDraft
import com.jarvis.core.agent.tools.CommunicationTools
import com.jarvis.core.agent.tools.ContactsTools
import com.jarvis.core.agent.tools.FilesTools
import com.jarvis.core.agent.tools.FilesTools.FileHit
import com.jarvis.core.agent.tools.MediaTools
import com.jarvis.core.agent.tools.SystemInfoTools
import com.jarvis.core.agent.tools.WebTools
import com.jarvis.core.agent.tools.WebTools.FetchedPage
import com.jarvis.core.database.repository.AuditLogEntry
import com.jarvis.core.database.repository.AuditLogRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {
    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ToolRegistry =
        ToolRegistry().apply {
            SystemInfoTools
                .all(
                    batteryPercent = { readBatteryPercent(context) },
                    storageFreeBytes = { readFreeBytes(context) },
                    networkState = { readNetworkState(context) },
                ).forEach { register(it) }

            CalendarTools
                .all(
                    insertEvent = { draft -> insertCalendarEvent(context, draft) },
                    queryEvents = { from, to -> queryCalendarEvents(context, from, to) },
                    insertReminder = { draft -> insertCalendarReminder(context, draft) },
                ).forEach { register(it) }

            ContactsTools
                .all(
                    lookup = { name -> lookupContacts(context, name) },
                ).forEach { register(it) }

            CommunicationTools
                .all(
                    sendSms = { to, body -> sendSms(context, to, body) },
                    placeCall = { number -> placeCall(context, number) },
                ).forEach { register(it) }

            FilesTools
                .all(
                    search = { query -> searchFiles(context, query) },
                ).forEach { register(it) }

            WebTools
                .all(
                    fetch = { url -> fetchUrl(okHttpClient, url) },
                ).forEach { register(it) }

            AlarmTools
                .all(
                    setAlarm = { at, label -> setAlarm(context, at, label) },
                ).forEach { register(it) }

            MediaTools
                .all(
                    adjust = { action, stream -> adjustVolume(context, action, stream) },
                ).forEach { register(it) }
        }

    @Provides
    @Singleton
    fun provideAuditLogger(repository: AuditLogRepository): AuditLogger =
        AuditLogger { record ->
            repository.record(
                AuditLogEntry(
                    agentRunId = record.agentRunId,
                    toolName = record.toolName,
                    tier = record.tier,
                    paramsRedactedJson = record.paramsRedactedJson,
                    resultStatus = record.resultStatus,
                    userConfirmed = record.userConfirmed,
                    timestamp = record.timestamp,
                ),
            )
        }

    // ---- calendar / contacts / communication platform bindings ----

    private suspend fun insertCalendarEvent(
        context: Context,
        draft: CalendarEventDraft,
    ): Result<Long> =
        runCatching {
            val values =
                android.content.ContentValues().apply {
                    put(CalendarContract.Events.TITLE, draft.title)
                    put(CalendarContract.Events.DESCRIPTION, draft.description)
                    put(CalendarContract.Events.EVENT_LOCATION, draft.location)
                    put(CalendarContract.Events.DTSTART, draft.startUtcMillis)
                    put(CalendarContract.Events.DTEND, draft.endUtcMillis)
                    put(CalendarContract.Events.CALENDAR_ID, primaryCalendarId(context) ?: 1L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                }
            val uri =
                context.contentResolver.insert(
                    CalendarContract.Events.CONTENT_URI,
                    values,
                ) ?: error("Calendar provider refused the insert")
            ContentUris.parseId(uri)
        }

    private suspend fun queryCalendarEvents(
        context: Context,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Result<List<CalendarEvent>> =
        runCatching {
            val projection =
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION,
                )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            context.contentResolver
                .query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(fromUtcMillis.toString(), toUtcMillis.toString()),
                    "${CalendarContract.Events.DTSTART} ASC",
                )
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                CalendarEvent(
                                    eventId = cursor.getLong(0),
                                    title = cursor.getString(1) ?: "(untitled)",
                                    startUtcMillis = cursor.getLong(2),
                                    endUtcMillis = cursor.getLong(3),
                                    location = cursor.getString(4),
                                ),
                            )
                        }
                    }
                } ?: error("Calendar provider unavailable")
        }

    private suspend fun insertCalendarReminder(
        context: Context,
        draft: ReminderDraft,
    ): Result<Long> =
        insertCalendarEvent(
            context,
            // Reminders are zero-duration events; the calendar's default reminder alert fires.
            CalendarEventDraft(
                title = draft.title,
                description = "Reminder set by Jarvis",
                startUtcMillis = draft.remindAtUtcMillis,
                endUtcMillis = draft.remindAtUtcMillis,
                location = null,
            ),
        )

    private fun primaryCalendarId(context: Context): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI
        return context.contentResolver
            .query(uri, arrayOf(CalendarContract.Calendars._ID), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private suspend fun lookupContacts(
        context: Context,
        name: String,
    ): Result<List<ContactsTools.ContactMatch>> =
        runCatching {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection =
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
            context.contentResolver
                .query(
                    uri,
                    projection,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$name%"),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                )
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                ContactsTools.ContactMatch(
                                    displayName = cursor.getString(0) ?: "(unnamed)",
                                    phone = cursor.getString(1),
                                ),
                            )
                        }
                    }.distinctBy { it.displayName to it.phone }
                } ?: error("Contacts provider unavailable")
        }

    private suspend fun sendSms(
        context: Context,
        to: String,
        body: String,
    ): Result<Unit> =
        runCatching {
            val manager = ContextCompat.getSystemService(context, SmsManager::class.java)
                ?: error("SMS is unavailable on this device")
            if (body.length <= 160) {
                manager.sendTextMessage(to, null, body, null, null)
            } else {
                manager.sendMultipartTextMessage(
                    to,
                    null,
                    manager.divideMessage(body),
                    null,
                    null,
                )
            }
        }

    private suspend fun placeCall(
        context: Context,
        number: String,
    ): Result<Unit> =
        runCatching {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

    private fun readBatteryPercent(context: Context): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
    }

    private fun readFreeBytes(context: Context): Long? =
        runCatching { StatFs(Environment.getDataDirectory().absolutePath).availableBytes }.getOrNull()

    private fun readNetworkState(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "offline"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        val online =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!online) return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "online"
        }
    }

    // ---- files / web / alarm / volume platform bindings ----

    /** MediaStore name search across the device's indexed files (API 29+ needs no permission). */
    private suspend fun searchFiles(
        context: Context,
        query: String,
    ): Result<List<FileHit>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // One union query across media collections keeps this a single round trip.
                val uri = MediaStore.Files.getContentUri("external")
                val projection =
                    arrayOf(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                    )
                context.contentResolver
                    .query(
                        uri,
                        projection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$query%"),
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                    )
                    ?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext() && size < 50) {
                                add(
                                    FileHit(
                                        displayName = cursor.getString(0) ?: "(unnamed)",
                                        path = cursor.getString(1) ?: "",
                                        sizeBytes = cursor.getLong(2),
                                        modifiedUtcMillis = cursor.getLong(3) * 1000L,
                                    ),
                                )
                            }
                        }
                    } ?: error("Media store unavailable")
            }
        }

    /** Fetch a URL and reduce it to readable plain text (no extra dependency: strip tags). */
    private suspend fun fetchUrl(
        client: OkHttpClient,
        url: String,
    ): Result<FetchedPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val contentType = response.header("Content-Type") ?: ""
                    if (!contentType.contains("text/", ignoreCase = true) &&
                        !contentType.contains("json", ignoreCase = true)
                    ) {
                        error("Not a text page (Content-Type: ${contentType.ifBlank { "unknown" }})")
                    }
                    val raw = response.body?.string() ?: error("Empty body")
                    val title =
                        Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                            .find(raw)
                            ?.groupValues
                            ?.get(1)
                            ?.let { decodeHtmlEntities(it).trim() }
                    FetchedPage(title = title, text = htmlToText(raw))
                }
            }
        }

    /** Strip scripts/styles/tags, collapse whitespace — readable text, not markup soup. */
    private fun htmlToText(html: String): String =
        html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("<[^>]+>"), " ")
            .let(::decodeHtmlEntities)
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun decodeHtmlEntities(text: String): String =
        text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

    /**
     * One-shot AlarmManager alarm: uses setExactAndAllowWhileIdle when the system grants it
     * (SCHEDULE_EXACT_ALARM on 31+), else falls back to setAndAllowWhileIdle — an alarm that
     * fires a bit late beats one that never registers.
     */
    private suspend fun setAlarm(
        context: Context,
        triggerAtUtcMillis: Long,
        label: String,
    ): Result<Unit> =
        runCatching {
            val manager = ContextCompat.getSystemService(context, AlarmManager::class.java)
                ?: error("Alarm manager unavailable")
            val pendingIntent =
                android.app.PendingIntent.getBroadcast(
                    context,
                    label.hashCode(),
                    Intent("com.jarvis.action.AGENT_ALARM").apply {
                        setPackage(context.packageName)
                        putExtra("label", label)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            val canExact =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                    manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtUtcMillis,
                    pendingIntent,
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtUtcMillis,
                    pendingIntent,
                )
            }
        }

    /** Adjust volume by one step; returns a human-readable post-state like "media volume 7/15". */
    private suspend fun adjustVolume(
        context: Context,
        action: String,
        stream: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager =
                    ContextCompat.getSystemService(context, AudioManager::class.java)
                        ?: error("Audio manager unavailable")
                val androidStream =
                    when (stream) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        else -> AudioManager.STREAM_MUSIC
                    }
                when (action) {
                    MediaTools.ACTION_MUTE -> manager.adjustStreamVolume(androidStream, AudioManager.ADJUST_MUTE, 0)
                    MediaTools.ACTION_UNMUTE -> manager.adjustStreamVolume(androidStream, AudioManager.ADJUST_UNMUTE, 0)
                    MediaTools.ACTION_UP -> manager.adjustStreamVolume(androidStream, AudioManager.ADJUST_RAISE, 0)
                    MediaTools.ACTION_DOWN -> manager.adjustStreamVolume(androidStream, AudioManager.ADJUST_LOWER, 0)
                }
                val current = manager.getStreamVolume(androidStream)
                val max = manager.getStreamMaxVolume(androidStream)
                "stream $stream at $current/$max"
            }
        }
}
