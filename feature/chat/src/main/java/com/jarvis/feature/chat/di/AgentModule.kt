package com.jarvis.feature.chat.di

import android.Manifest
import android.app.AlarmManager
import android.provider.AlarmClock
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.jarvis.core.agent.AgentContinuationTracker
import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AssistantNotificationManager
import com.jarvis.core.agent.AttachmentProcessor
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.DefaultToolPolicy
import com.jarvis.core.agent.ReversibleActionExecutor
import com.jarvis.core.agent.RoutineExecutionRunner
import com.jarvis.core.agent.RoutineScheduler
import com.jarvis.core.agent.RoutineWorkScheduler
import com.jarvis.core.agent.TaskEngine
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolLoader
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.bridge.BridgeCoordinator
import com.jarvis.core.agent.bridge.CommandPolicyEngine
import com.jarvis.core.agent.bridge.SandboxBridge
import com.jarvis.core.agent.bridge.ShizukuBridge
import com.jarvis.core.agent.tools.AlarmTools
import com.jarvis.core.agent.tools.BridgeTools
import com.jarvis.core.agent.tools.CalculatorTool
import com.jarvis.core.agent.tools.CalendarTools
import com.jarvis.core.agent.tools.CalendarTools.CalendarEvent
import com.jarvis.core.agent.tools.CalendarTools.CalendarEventDraft
import com.jarvis.core.agent.tools.CalendarTools.ReminderDraft
import com.jarvis.core.agent.tools.CommunicationTools
import com.jarvis.core.agent.tools.ContactsTools
import com.jarvis.core.agent.tools.DeviceTools
import com.jarvis.core.agent.tools.FilesTools
import com.jarvis.core.agent.tools.FilesTools.FileHit
import com.jarvis.core.agent.tools.MediaTools
import com.jarvis.core.agent.tools.MemoryTools
import com.jarvis.core.agent.tools.SystemInfoTools
import com.jarvis.core.agent.tools.TaskTools
import com.jarvis.core.agent.tools.UndoActionTool
import com.jarvis.core.agent.tools.WebTools
import com.jarvis.core.agent.tools.WebTools.FetchedPage
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.ReversibleAction
import com.jarvis.core.database.repository.AuditLogEntry
import com.jarvis.core.database.repository.AuditLogRepository
import com.jarvis.core.database.repository.MemoryRepository
import com.jarvis.core.database.repository.ProviderRepository
import com.jarvis.core.database.repository.ReversibleActionRepository
import com.jarvis.core.database.repository.RoutineRepository
import com.jarvis.core.database.repository.TaskRepository
import com.jarvis.core.database.repository.ToolCatalogRepository
import com.jarvis.core.network.ProviderManager
import com.jarvis.feature.chat.worker.WorkRoutineScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {
    @Provides
    @Singleton
    fun provideCommandPolicyEngine(): CommandPolicyEngine = CommandPolicyEngine()

    @Provides
    @Singleton
    fun provideSandboxBridge(@ApplicationContext context: Context): SandboxBridge = SandboxBridge(context)

    @Provides
    @Singleton
    fun provideShizukuBridge(@ApplicationContext context: Context): ShizukuBridge = ShizukuBridge(context)

    @Provides
    @Singleton
    fun provideBridgeCoordinator(
        sandboxBridge: SandboxBridge,
        shizukuBridge: ShizukuBridge,
        policyEngine: CommandPolicyEngine,
    ): BridgeCoordinator = BridgeCoordinator(
        sandboxBridge = sandboxBridge,
        shizukuBridge = shizukuBridge,
        policyEngine = policyEngine,
    )

    @Provides
    @Singleton
    fun provideAttachmentProcessor(@ApplicationContext context: Context): AttachmentProcessor =
        AttachmentProcessor(context)

    @Provides
    @Singleton
    fun provideBuiltInTools(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        memoryRepository: MemoryRepository,
        taskRepository: TaskRepository,
        actionRepository: ReversibleActionRepository,
        bridgeCoordinator: BridgeCoordinator,
        notificationManager: AssistantNotificationManager,
        reversibleActionExecutor: ReversibleActionExecutor,
    ): @JvmSuppressWildcards List<Tool> {
        val tools = mutableListOf<Tool>()
        tools.addAll(
            SystemInfoTools.all(
                batteryPercent = { readBatteryPercent(context) },
                storageFreeBytes = { readFreeBytes(context) },
                networkState = { readNetworkState(context) },
            ),
        )
        tools.addAll(
            CalendarTools.all(
                insertEvent = { draft -> insertCalendarEvent(context, draft, actionRepository) },
                queryEvents = { from, to -> queryCalendarEvents(context, from, to) },
                insertReminder = { draft -> insertCalendarReminder(context, draft) },
                updateEvent = { id, draft -> updateCalendarEvent(context, id, draft) },
                deleteEvent = { id -> deleteCalendarEvent(context, id) },
            ),
        )
        tools.addAll(
            ContactsTools.all(
                lookup = { name -> lookupContacts(context, name) },
            ),
        )
        tools.addAll(
            CommunicationTools.all(
                sendSms = { to, body -> sendSms(context, to, body) },
                placeCall = { number -> placeCall(context, number) },
            ),
        )
        tools.addAll(
            FilesTools.all(
                search = { query -> searchFiles(context, query) },
                read = { path -> readLocalFile(context, path) },
                create = { fileName, content, location -> createNamedFile(context, fileName, content, location) },
            ),
        )
        tools.addAll(
            WebTools.all(
                fetch = { url -> fetchUrl(okHttpClient, url) },
                search = { query, maxResults -> searchWeb(okHttpClient, query, maxResults) },
            ),
        )
        tools.addAll(
            AlarmTools.all(
                setAlarm = { at, label -> setAlarm(context, at, label) },
                setTimer = { durationSec, label -> setTimer(context, durationSec, label) },
            ),
        )
        tools.addAll(
            DeviceTools.all(
                launchApp = { target -> launchApp(context, target) },
                copyClipboard = { text, label -> copyToClipboard(context, text, label) },
                readClipboard = { readClipboard(context) },
                showNotification = { title, msg -> showNotification(notificationManager, title, msg) },
            ),
        )
        tools.addAll(
            MediaTools.all(
                adjust = { action, stream -> adjustVolume(context, action, stream) },
            ),
        )
        tools.addAll(MemoryTools.all(memoryRepository))
        tools.addAll(TaskTools.all(taskRepository))
        tools.addAll(BridgeTools.all(bridgeCoordinator))
        tools.add(CalculatorTool.create())
        tools.add(UndoActionTool.create(reversibleActionExecutor, actionRepository))
        return tools
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        builtInTools: @JvmSuppressWildcards List<Tool>,
    ): ToolRegistry =
        ToolRegistry().apply {
            builtInTools.forEach { register(it) }
        }

    @Provides
    fun provideAgentContinuationTracker(): AgentContinuationTracker =
        AgentContinuationTracker()

    @Provides
    @Singleton
    fun provideToolLoader(
        toolCatalogRepository: ToolCatalogRepository,
        toolRegistry: ToolRegistry,
        auditLogRepository: AuditLogRepository,
    ): ToolLoader =
        ToolLoader(
            catalogRepository = toolCatalogRepository,
            registry = toolRegistry,
            auditLogRepository = auditLogRepository,
        )


    @Provides
    @Singleton
    fun provideAssistantNotificationManager(
        @ApplicationContext context: Context,
    ): AssistantNotificationManager = AssistantNotificationManager(context)

    @Provides
    @Singleton
    fun provideTaskEngine(
        taskRepository: TaskRepository,
        operationRepository: com.jarvis.core.database.repository.OperationRepository,
    ): TaskEngine = TaskEngine(taskRepository, operationRepository)

    @Provides
    @Singleton
    fun provideRoutineExecutionRunner(
        providerRepository: ProviderRepository,
        providerManager: ProviderManager,
        toolRegistry: ToolRegistry,
        auditLogger: AuditLogger,
        memoryRepository: MemoryRepository,
    ): RoutineExecutionRunner = RoutineExecutionRunner { task, routine ->
        runCatching {
            val providers = providerRepository.observeProviders().first()
            val config = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
                ?: error("No active LLM provider configured for routine execution.")
            val provider = providerManager.adapterFor(config)
            val runner = AgentRunner(
                registry = toolRegistry,
                audit = auditLogger,
                confirmationGate = { _, _ -> false },
                toolPolicy = com.jarvis.core.agent.BackgroundToolPolicy(),
                stepCap = 10,
            )
            val memories = memoryRepository.getActiveNonPrivate()
            val memoryContext = if (memories.isNotEmpty()) {
                memories.joinToString("\n") { "- [${it.category.name}]: ${it.content}" }
            } else null

            val request = AgentRunRequest(
                provider = provider,
                modelId = config.model ?: "default",
                messages = listOf(
                    Message(
                        conversationId = "routine-${routine.id}",
                        role = MessageRole.USER,
                        content = "Execute routine: ${routine.name}\nGoal: ${routine.goal}",
                    )
                ),
                agentRunId = task.id,
                memoryContext = memoryContext,
            )

            var finalAnswer: String? = null
            var failureError: String? = null
            runner.run(request).collect { event ->
                when (event) {
                    is AgentEvent.FinalAnswer -> finalAnswer = event.text
                    is AgentEvent.Failed -> failureError = "${event.code}: ${event.message}"
                    is AgentEvent.ToolCancelled -> failureError = "Tool '${event.name}' required user confirmation which is unavailable in background routines."
                    is AgentEvent.StepCapReached -> finalAnswer = finalAnswer ?: "Routine step cap reached."
                    else -> Unit
                }
            }

            if (failureError != null) {
                error(failureError!!)
            }

            finalAnswer ?: "Routine executed successfully."
        }
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideRoutineWorkScheduler(workManager: WorkManager): RoutineWorkScheduler =
        WorkRoutineScheduler(workManager)

    @Provides
    @Singleton
    fun provideRoutineScheduler(
        routineRepository: RoutineRepository,
        taskRepository: TaskRepository,
        taskEngine: TaskEngine,
        runner: RoutineExecutionRunner,
        notificationManager: AssistantNotificationManager,
        workScheduler: RoutineWorkScheduler,
    ): RoutineScheduler = RoutineScheduler(
        routineRepository = routineRepository,
        taskRepository = taskRepository,
        taskEngine = taskEngine,
        runner = runner,
        notifications = notificationManager,
        workScheduler = workScheduler,
    )

    @Provides
    @Singleton
    fun provideReversibleActionExecutor(
        actionRepository: ReversibleActionRepository,
        memoryRepository: MemoryRepository,
        @ApplicationContext context: Context,
    ): ReversibleActionExecutor =
        ReversibleActionExecutor(actionRepository, memoryRepository).apply {
            setExternalRevertHandler { actionType, target, _ ->
                runCatching {
                    if (actionType == "calendar_event") {
                        val eventId = target.toLongOrNull() ?: error("Invalid calendar event ID $target")
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                        val deleted = context.contentResolver.delete(uri, null, null)
                        deleted > 0
                    } else {
                        false
                    }
                }
            }
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




    private fun requirePermission(
        context: Context,
        permission: String,
    ) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            error("Not permitted: grant it in Settings → Permissions, then ask again.")
        }
    }

    private suspend fun insertCalendarEvent(
        context: Context,
        draft: CalendarEventDraft,
        actionRepository: ReversibleActionRepository? = null,
    ): Result<Long> =
        runCatching {
            requirePermission(context, Manifest.permission.READ_CALENDAR)
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
            val id = ContentUris.parseId(uri)

            actionRepository?.recordAction(
                ReversibleAction(
                    actionType = "calendar_event",
                    target = id.toString(),
                    inverseActionJson = "{\"action\":\"delete_event\",\"eventId\":$id}",
                )
            )

            id
        }

    private suspend fun queryCalendarEvents(
        context: Context,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Result<List<CalendarEvent>> =
        runCatching {
            requirePermission(context, Manifest.permission.READ_CALENDAR)
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
            requirePermission(context, Manifest.permission.READ_CONTACTS)
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
            requirePermission(context, Manifest.permission.SEND_SMS)
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
            requirePermission(context, Manifest.permission.CALL_PHONE)
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



    /** MediaStore name search across the device's indexed files. */
    private suspend fun searchFiles(
        context: Context,
        query: String,
    ): Result<List<FileHit>> =
        withContext(Dispatchers.IO) {
            runCatching {
                try {
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
                } catch (e: SecurityException) {
                    error("Device storage permission missing: grant Storage permission in Settings → Permissions, then ask again.")
                }
            }
        }


    private fun ensurePublicHttpUrl(url: String) = ensurePublicHttpUrlChecked(url)

    /** Fetch a URL and reduce it to readable plain text, with GitHub repo fallback. */
    private suspend fun fetchUrl(
        client: OkHttpClient,
        url: String,
    ): Result<FetchedPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensurePublicHttpUrl(url)
                val userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain,*/*;q=0.8")
                    .get()
                    .build()

                var response = client.newCall(request).execute()
                val responseCode = response.code

                // If GitHub returns 404/failure on a repo URL (or raw/blob), try GitHub API or README fallback
                val gitHubRepoRegex = Regex("""^https?://(?:www\.)?github\.com/([^/]+)/([^/#?]+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
                val gitHubMatch = gitHubRepoRegex.find(url)

                if ((!response.isSuccessful || responseCode == 404) && gitHubMatch != null) {
                    response.close()
                    val owner = gitHubMatch.groupValues[1]
                    val repo = gitHubMatch.groupValues[2].removeSuffix(".git")
                    val fallbackUrls = listOf(
                        "https://raw.githubusercontent.com/$owner/$repo/main/README.md",
                        "https://raw.githubusercontent.com/$owner/$repo/master/README.md",
                        "https://api.github.com/repos/$owner/$repo",
                    )
                    var fallbackPage: FetchedPage? = null
                    for (fallbackUrl in fallbackUrls) {
                        val fbRequest = Request.Builder()
                            .url(fallbackUrl)
                            .header("User-Agent", "Jarvis-Android-Assistant")
                            .header("Accept", "application/vnd.github.v3+json, text/plain")
                            .get()
                            .build()
                        val fbResp = runCatching { client.newCall(fbRequest).execute() }.getOrNull()
                        if (fbResp != null && fbResp.isSuccessful) {
                            val bodyText = fbResp.body?.string() ?: ""
                            fbResp.close()
                            if (bodyText.isNotBlank()) {
                                fallbackPage = FetchedPage(
                                    title = "$owner/$repo (GitHub)",
                                    text = if (fallbackUrl.endsWith(".md")) bodyText else htmlToText(bodyText),
                                )
                                break
                            }
                        } else {
                            fbResp?.close()
                        }
                    }
                    if (fallbackPage != null) {
                        return@runCatching fallbackPage
                    }
                    error("HTTP $responseCode: Could not fetch GitHub repository at $url")
                }

                response.use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val contentType = resp.header("Content-Type") ?: ""
                    if (!contentType.contains("text/", ignoreCase = true) &&
                        !contentType.contains("json", ignoreCase = true) &&
                        !contentType.contains("markdown", ignoreCase = true)
                    ) {
                        error("Not a text page (Content-Type: ${contentType.ifBlank { "unknown" }})")
                    }
                    val raw = resp.body?.string() ?: error("Empty body")
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

    /** Search the public web using DuckDuckGo with fallback to Wikipedia API. */
    private suspend fun searchWeb(
        client: OkHttpClient,
        query: String,
        maxResults: Int,
    ): Result<List<com.jarvis.core.agent.tools.WebTools.SearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = mutableListOf<com.jarvis.core.agent.tools.WebTools.SearchResult>()
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

                // 1. DuckDuckGo Lite search (fast, zero JS requirement, highly reliable on mobile)
                try {
                    val formBody = okhttp3.FormBody.Builder()
                        .add("q", query)
                        .build()
                    val ddgLiteRequest = Request.Builder()
                        .url("https://lite.duckduckgo.com/lite/")
                        .post(formBody)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                        .header("Referer", "https://lite.duckduckgo.com/")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()

                    client.newCall(ddgLiteRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            val html = response.body?.string() ?: ""
                            val linkRegex = Regex(
                                """<a\b[^>]*href=['"]([^'"]+)['"][^>]*class=['"]result-link['"][^>]*>([\s\S]*?)</a>|<a\b[^>]*class=['"]result-link['"][^>]*href=['"]([^'"]+)['"][^>]*>([\s\S]*?)</a>""",
                                RegexOption.IGNORE_CASE,
                            )
                            val snippetRegex = Regex(
                                """<td\b[^>]*class=['"]result-snippet['"][^>]*>([\s\S]*?)</td>""",
                                RegexOption.IGNORE_CASE,
                            )

                            val linkMatches = linkRegex.findAll(html).toList()
                            val snippetMatches = snippetRegex.findAll(html).toList()

                            for (i in linkMatches.indices) {
                                if (results.size >= maxResults) break
                                val m = linkMatches[i]
                                val rawUrl = m.groupValues[1].ifEmpty { m.groupValues[3] }
                                val rawTitle = m.groupValues[2].ifEmpty { m.groupValues[4] }
                                val title = decodeHtmlEntities(rawTitle.replace(Regex("<[^>]+>"), "").trim())
                                val snippet = if (i < snippetMatches.size) {
                                    decodeHtmlEntities(snippetMatches[i].groupValues[1].replace(Regex("<[^>]+>"), "").trim())
                                } else ""

                                val resolvedUrl = when {
                                    rawUrl.contains("uddg=") -> {
                                        val match = Regex("uddg=([^&]+)").find(rawUrl)
                                        if (match != null) java.net.URLDecoder.decode(match.groupValues[1], "UTF-8") else rawUrl
                                    }
                                    rawUrl.startsWith("//") -> "https:$rawUrl"
                                    else -> rawUrl
                                }

                                if (title.isNotBlank() && resolvedUrl.isNotBlank() && !resolvedUrl.contains("duckduckgo.com")) {
                                    results.add(
                                        com.jarvis.core.agent.tools.WebTools.SearchResult(
                                            title = title,
                                            url = resolvedUrl,
                                            snippet = snippet,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("AgentModule", "DDG Lite search failed: ${e.message}")
                }

                // 2. DuckDuckGo HTML search fallback
                if (results.isEmpty()) {
                    try {
                        val formBody = okhttp3.FormBody.Builder()
                            .add("q", query)
                            .build()
                        val ddgRequest = Request.Builder()
                            .url("https://html.duckduckgo.com/html/")
                            .post(formBody)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                            .header("Referer", "https://html.duckduckgo.com/")
                            .build()

                        client.newCall(ddgRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                val html = response.body?.string() ?: ""
                                val linkRegex = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
                                val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)

                                val links = linkRegex.findAll(html).toList()
                                val snippets = snippetRegex.findAll(html).toList()

                                for (i in links.indices) {
                                    if (results.size >= maxResults) break
                                    val rawUrl = links[i].groupValues[1]
                                    val title = decodeHtmlEntities(links[i].groupValues[2].replace(Regex("<[^>]+>"), "").trim())
                                    val snippet = if (i < snippets.size) {
                                        decodeHtmlEntities(snippets[i].groupValues[1].replace(Regex("<[^>]+>"), "").trim())
                                    } else ""

                                    val resolvedUrl = if (rawUrl.contains("uddg=")) {
                                        val match = Regex("uddg=([^&]+)").find(rawUrl)
                                        if (match != null) java.net.URLDecoder.decode(match.groupValues[1], "UTF-8") else rawUrl
                                    } else if (rawUrl.startsWith("//")) {
                                        "https:$rawUrl"
                                    } else {
                                        rawUrl
                                    }

                                    if (title.isNotBlank() && resolvedUrl.isNotBlank()) {
                                        results.add(
                                            com.jarvis.core.agent.tools.WebTools.SearchResult(
                                                title = title,
                                                url = resolvedUrl,
                                                snippet = snippet,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("AgentModule", "DDG HTML search attempt failed: ${e.message}")
                    }
                }

                // 3. DuckDuckGo Instant Answer API fallback
                if (results.isEmpty()) {
                    try {
                        val apiUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=0"
                        val apiRequest = Request.Builder()
                            .url(apiUrl)
                            .get()
                            .header("User-Agent", "JarvisAssistant/1.0")
                            .build()
                        client.newCall(apiRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                val bodyStr = response.body?.string() ?: ""
                                val root = org.json.JSONObject(bodyStr)
                                val heading = root.optString("Heading")
                                val abstractText = root.optString("AbstractText")
                                val abstractUrl = root.optString("AbstractURL")
                                if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
                                    results.add(
                                        com.jarvis.core.agent.tools.WebTools.SearchResult(
                                            title = heading.ifBlank { query },
                                            url = abstractUrl,
                                            snippet = abstractText,
                                        ),
                                    )
                                }
                                val related = root.optJSONArray("RelatedTopics")
                                if (related != null) {
                                    for (i in 0 until minOf(related.length(), maxResults - results.size)) {
                                        val item = related.optJSONObject(i) ?: continue
                                        val rText = item.optString("Text")
                                        val rUrl = item.optString("FirstURL")
                                        if (rText.isNotBlank() && rUrl.isNotBlank()) {
                                            results.add(
                                                com.jarvis.core.agent.tools.WebTools.SearchResult(
                                                    title = rText.take(60),
                                                    url = rUrl,
                                                    snippet = rText,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("AgentModule", "DDG Instant Answer API failed: ${e.message}")
                    }
                }

                // 4. Wikipedia API fallback
                if (results.isEmpty()) {
                    try {
                        val wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&format=json&utf8=1"
                        val wikiRequest = Request.Builder()
                            .url(wikiUrl)
                            .get()
                            .header("User-Agent", "JarvisAssistant/1.0")
                            .build()

                        client.newCall(wikiRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                val jsonStr = response.body?.string() ?: ""
                                val root = org.json.JSONObject(jsonStr)
                                val searchArr = root.optJSONObject("query")?.optJSONArray("search")
                                if (searchArr != null) {
                                    for (i in 0 until minOf(searchArr.length(), maxResults)) {
                                        val item = searchArr.getJSONObject(i)
                                        val title = item.optString("title")
                                        val pageId = item.optLong("pageid")
                                        val snippet = decodeHtmlEntities(
                                            item.optString("snippet").replace(Regex("<[^>]+>"), "").trim(),
                                        )
                                        val pageUrl = "https://en.wikipedia.org/?curid=$pageId"
                                        results.add(
                                            com.jarvis.core.agent.tools.WebTools.SearchResult(
                                                title = title,
                                                url = pageUrl,
                                                snippet = snippet,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("AgentModule", "Wikipedia search fallback failed: ${e.message}")
                    }
                }

                results
            }
        }


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

    private suspend fun updateCalendarEvent(
        context: Context,
        eventId: Long,
        draft: CalendarEventDraft,
    ): Result<Unit> =
        runCatching {
            requirePermission(context, Manifest.permission.WRITE_CALENDAR)
            val values =
                android.content.ContentValues().apply {
                    put(CalendarContract.Events.TITLE, draft.title)
                    put(CalendarContract.Events.DESCRIPTION, draft.description)
                    put(CalendarContract.Events.EVENT_LOCATION, draft.location)
                    put(CalendarContract.Events.DTSTART, draft.startUtcMillis)
                    put(CalendarContract.Events.DTEND, draft.endUtcMillis)
                }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated == 0) error("Event $eventId not found or could not be updated")
        }

    private suspend fun deleteCalendarEvent(
        context: Context,
        eventId: Long,
    ): Result<Unit> =
        runCatching {
            requirePermission(context, Manifest.permission.WRITE_CALENDAR)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted == 0) error("Event $eventId not found or could not be deleted")
        }

    private suspend fun setTimer(
        context: Context,
        durationSeconds: Int,
        label: String,
    ): Result<Unit> =
        runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

    private fun hasStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private suspend fun readLocalFile(
        context: Context,
        path: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolvedFile = resolveFilePath(context, path)
                if (!resolvedFile.exists()) error("File does not exist: $path")
                if (resolvedFile.isDirectory) error("Path is a directory: $path")
                val isExternal = !resolvedFile.absolutePath.startsWith(context.filesDir.absolutePath) &&
                    !resolvedFile.absolutePath.startsWith(context.cacheDir.absolutePath)
                if (isExternal && !resolvedFile.canRead() && !hasStoragePermission(context)) {
                    error("Device storage permission missing: grant Storage permission in Settings → Permissions, then ask again.")
                }
                try {
                    if (resolvedFile.length() > 500_000) {
                        resolvedFile.bufferedReader().use { it.readText().take(500_000) + "\n...(truncated)" }
                    } else {
                        resolvedFile.readText()
                    }
                } catch (e: SecurityException) {
                    error("Device storage permission missing: grant Storage permission in Settings → Permissions, then ask again.")
                } catch (e: Exception) {
                    if (e.message?.contains("EACCES", ignoreCase = true) == true ||
                        e.message?.contains("Permission denied", ignoreCase = true) == true
                    ) {
                        error("Device storage permission denied: grant Storage permission in Settings → Permissions, then ask again.")
                    } else {
                        throw e
                    }
                }
            }
        }

    private fun resolveFilePath(context: Context, path: String): java.io.File {
        val trimmed = path.trim()
        val lower = trimmed.lowercase()
        return when {
            lower == "/download" || lower == "download" || lower == "/downloads" || lower == "downloads" -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            lower.startsWith("/download/") || lower.startsWith("download/") -> {
                val sub = trimmed.substringAfter("download/").removePrefix("/")
                java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
            }
            lower.startsWith("/downloads/") || lower.startsWith("downloads/") -> {
                val sub = trimmed.substringAfter("downloads/").removePrefix("/")
                java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
            }
            lower == "/documents" || lower == "documents" -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }
            lower.startsWith("/documents/") || lower.startsWith("documents/") -> {
                val sub = trimmed.substringAfter("documents/").removePrefix("/")
                java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), sub)
            }
            trimmed.startsWith("/") -> java.io.File(trimmed)
            else -> java.io.File(context.filesDir, trimmed)
        }
    }

    private suspend fun createNamedFile(
        context: Context,
        fileName: String,
        content: String,
        location: String?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanName = java.io.File(fileName).name
                val loc = location?.lowercase()?.trim() ?: "downloads"

                if (loc == "app_private" || loc == "internal" || loc == "private") {
                    val file = java.io.File(context.filesDir, cleanName)
                    file.writeText(content)
                    return@runCatching file.absolutePath
                }

                val mimeType = when {
                    cleanName.endsWith(".json", ignoreCase = true) -> "application/json"
                    cleanName.endsWith(".csv", ignoreCase = true) -> "text/csv"
                    cleanName.endsWith(".html", ignoreCase = true) || cleanName.endsWith(".htm", ignoreCase = true) -> "text/html"
                    cleanName.endsWith(".xml", ignoreCase = true) -> "text/xml"
                    cleanName.endsWith(".md", ignoreCase = true) -> "text/markdown"
                    else -> "text/plain"
                }

                val isDocuments = loc == "documents" || loc == "document" || loc == "doc"
                val relativePath = if (isDocuments) Environment.DIRECTORY_DOCUMENTS else Environment.DIRECTORY_DOWNLOADS

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    }
                    val collection = if (isDocuments) {
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    val uri = context.contentResolver.insert(collection, values)
                        ?: error("Failed to insert file into MediaStore $relativePath")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("Failed to open output stream for MediaStore uri $uri")
                    "$relativePath/$cleanName"
                } else {
                    val baseDir = Environment.getExternalStoragePublicDirectory(relativePath)
                    baseDir.mkdirs()
                    val targetFile = java.io.File(baseDir, cleanName)
                    targetFile.writeText(content)
                    targetFile.absolutePath
                }
            }
        }

    private suspend fun launchApp(
        context: Context,
        target: String,
    ): Result<Unit> =
        runCatching {
            val pm = context.packageManager
            val clean = target.lowercase().trim()

            // 1. Direct intent actions for common device utilities like camera/browser
            val directIntent = when (clean) {
                "camera", "open camera", "take a picture", "take photo" -> {
                    val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (camIntent.resolveActivity(pm) != null) {
                        camIntent
                    } else {
                        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val apps = pm.queryIntentActivities(mainIntent, 0)
                        val camApp = apps.firstOrNull {
                            it.activityInfo.packageName.contains("camera", ignoreCase = true) ||
                                it.loadLabel(pm).toString().contains("camera", ignoreCase = true)
                        }
                        camApp?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                    }
                }
                else -> null
            }

            val intent = directIntent
                ?: pm.getLaunchIntentForPackage(target.trim())
                ?: run {
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val activities = pm.queryIntentActivities(launcherIntent, 0)
                    val match = activities.firstOrNull {
                        val label = it.loadLabel(pm).toString()
                        label.equals(clean, ignoreCase = true)
                    } ?: activities.firstOrNull {
                        val label = it.loadLabel(pm).toString()
                        label.contains(clean, ignoreCase = true)
                    } ?: activities.firstOrNull {
                        val pkg = it.activityInfo.packageName
                        pkg.contains(clean, ignoreCase = true)
                    }
                    match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                }
                ?: run {
                    val installed = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrNull() ?: emptyList()
                    val match = installed.firstOrNull {
                        val label = pm.getApplicationLabel(it).toString()
                        label.equals(clean, ignoreCase = true) || label.contains(clean, ignoreCase = true)
                    }
                    match?.let { pm.getLaunchIntentForPackage(it.packageName) }
                }
                ?: run {
                    val fallbackPackage = when (clean) {
                        "youtube" -> "com.google.android.youtube"
                        "chrome", "google chrome", "browser" -> "com.android.chrome"
                        "maps", "google maps" -> "com.google.android.apps.maps"
                        "gmail", "email" -> "com.google.android.gm"
                        "photos", "google photos" -> "com.google.android.apps.photos"
                        "calendar", "google calendar" -> "com.google.android.calendar"
                        "play store", "google play", "store" -> "com.android.vending"
                        "clock" -> "com.google.android.deskclock"
                        "calculator" -> "com.google.android.calculator"
                        "camera" -> "com.google.android.GoogleCamera"
                        else -> null
                    }
                    fallbackPackage?.let { pm.getLaunchIntentForPackage(it) }
                }
                ?: error("Could not find installed application matching \"$target\"")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

    private suspend fun copyToClipboard(
        context: Context,
        text: String,
        label: String?,
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(label ?: "Jarvis", text)
                clipboard.setPrimaryClip(clip)
            }
        }

    private suspend fun readClipboard(
        context: Context,
    ): Result<String?> =
        withContext(Dispatchers.Main) {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context)?.toString()
                } else {
                    null
                }
            }
        }

    private suspend fun showNotification(
        notificationManager: AssistantNotificationManager,
        title: String,
        message: String,
    ): Result<Unit> =
        runCatching {
            notificationManager.notifyCustom(title, message)
        }
}


internal fun ensurePublicHttpUrlChecked(url: String) {
    val parsed = url.toHttpUrlOrNull() ?: error("Not a valid URL")
    if (parsed.scheme != "http" && parsed.scheme != "https") error("Only http(s) URLs are allowed")
    val host = parsed.host
    if (host == "localhost") error("Localhost is not allowed")
    val addresses = java.net.InetAddress.getAllByName(host)
    if (addresses.isEmpty()) error("Host did not resolve")
    for (address in addresses) {
        val blocked =
            address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isAnyLocalAddress ||
                address.isMulticastAddress ||


                (address is java.net.Inet4Address &&
                    (address.address[0].toInt() and 0xFF) == 169 &&
                    (address.address[1].toInt() and 0xFF) == 254)
        if (blocked) error("Refusing to fetch a private or local address")
    }
}
