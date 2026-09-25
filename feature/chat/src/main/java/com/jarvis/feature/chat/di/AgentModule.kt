package com.jarvis.feature.chat.di

import android.Manifest
import android.app.AlarmManager
import android.app.SearchManager
import android.provider.AlarmClock
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.Locale
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
import android.provider.Settings
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.telephony.SmsManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.jarvis.core.agent.AgentEvent
import com.jarvis.core.agent.AgentRunRequest
import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AssistantNotificationManager
import com.jarvis.core.agent.AttachmentProcessor
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.DefaultToolPolicy
import com.jarvis.core.agent.ReversibleActionExecutor
import com.jarvis.core.agent.RoutineExecutionRunner
import com.jarvis.core.agent.RoutineScheduler
import com.jarvis.core.agent.RoutineWorkScheduler
import com.jarvis.core.agent.TaskEngine
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolExecutor
import com.jarvis.core.agent.ToolLoader
import android.view.KeyEvent
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.bridge.BridgeCoordinator
import com.jarvis.core.agent.bridge.CommandPolicyEngine
import com.jarvis.core.agent.bridge.SandboxBridge
import com.jarvis.core.agent.bridge.ShizukuBridge
import com.jarvis.core.agent.automation.DefaultPhoneAgent
import com.jarvis.core.agent.automation.JarvisAccessibilityService
import com.jarvis.core.agent.automation.PhoneAgent
import com.jarvis.core.agent.tools.AlarmTools
import com.jarvis.core.agent.tools.AutomationTools
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
        providerRepository: ProviderRepository,
        providerManager: ProviderManager,
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
                setFlashlight = { enabled -> setFlashlight(context, enabled) },
                toggleBluetooth = { enabled -> toggleBluetooth(context, enabled) },
                openSettings = { settingType -> openSettings(context, settingType) },
                listInstalledApps = { query -> listInstalledApps(context, query) },
                installApp = { target -> installAppFromStore(context, target) },
            ),
        )
        val phoneAgent = DefaultPhoneAgent(
            serviceProvider = { JarvisAccessibilityService.instance },
            launchApp = { target -> launchApp(context, target) },
            llmProvider = {
                val providers = providerRepository.observeProviders().first()
                val activeId = providerManager.active.value
                val config = (if (activeId != null) providers.firstOrNull { it.id == activeId } else null)
                    ?: providers.firstOrNull { it.isDefault }
                    ?: providers.firstOrNull()
                config?.let { providerManager.adapterFor(it) }
            },
            modelIdProvider = {
                val providers = providerRepository.observeProviders().first()
                val activeId = providerManager.active.value
                val config = (if (activeId != null) providers.firstOrNull { it.id == activeId } else null)
                    ?: providers.firstOrNull { it.isDefault }
                    ?: providers.firstOrNull()
                config?.model?.takeIf { it.isNotBlank() } ?: "default"
            },
            phoneActionModel = com.jarvis.core.agent.automation.engine.LiteRtPhoneActionModel(
                File(context.filesDir, "models/functiongemma-mobile-actions_q8_ekv1024.litertlm"),
            ),
            requirePhoneActionModel = true,
        )
        tools.addAll(
            AutomationTools.all(
                phoneAgent = phoneAgent,
            ),
        )
        tools.addAll(
            MediaTools.all(
                adjust = { action, stream -> adjustVolume(context, action, stream) },
                play = { query, appName -> playMedia(context, query, appName) },
                control = { action -> controlMedia(context, action) },
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
    ): ToolRegistry {
        val registry = ToolRegistry()
        builtInTools.forEach { registry.register(it) }
        return registry
    }

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
        toolExecutor: ToolExecutor,
    ): TaskEngine = TaskEngine(taskRepository, operationRepository, toolExecutor)

    @Provides
    @Singleton
    fun provideRoutineExecutionRunner(
        providerRepository: ProviderRepository,
        providerManager: ProviderManager,
        toolRegistry: ToolRegistry,
        auditLogger: AuditLogger,
        memoryRepository: MemoryRepository,
        confirmationGate: ConfirmationGate,
        toolExecutor: ToolExecutor,
    ): RoutineExecutionRunner = RoutineExecutionRunner { task, routine ->
        runCatching {
            val providers = providerRepository.observeProviders().first()
            val config = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
                ?: error("No active LLM provider configured for routine execution.")
            val provider = providerManager.adapterFor(config)
            val runner = AgentRunner(
                registry = toolRegistry,
                audit = auditLogger,
                confirmationGate = confirmationGate,
                toolPolicy = com.jarvis.core.agent.BackgroundToolPolicy(),
                stepCap = com.jarvis.core.agent.AgentRunner.DEFAULT_STEP_CAP,
                toolExecutor = toolExecutor,
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
                timeoutMillis = 300_000L,
            )

            var finalAnswer: String? = null
            var failureError: String? = null
            val executedTools = mutableListOf<String>()
            runner.run(request).collect { event ->
                when (event) {
                    is AgentEvent.FinalAnswer -> finalAnswer = event.text
                    is AgentEvent.Failed -> failureError = "${event.code}: ${event.message}"
                    is AgentEvent.ToolCancelled -> failureError = "Tool '${event.name}' required user confirmation which is unavailable in background routines."
                    is AgentEvent.StepCapReached -> finalAnswer = finalAnswer ?: "Routine step cap reached."
                    is AgentEvent.ToolExecuted -> executedTools += event.name
                    else -> Unit
                }
            }

            if (failureError != null) {
                error(failureError!!)
            }

            finalAnswer?.takeIf { !com.jarvis.core.agent.execution.ToolResultResponseDeriver.isGenericFallback(it) }
                ?: if (executedTools.isNotEmpty()) {
                    "Background routine executed tools: ${executedTools.joinToString()}."
                } else {
                    "The background routine produced no tool result; no actions were verified."
                }
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
            requirePermission(context, Manifest.permission.WRITE_CALENDAR)
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

    /** Search the public web using Brave Search API with Wikipedia as fallback. */
    private suspend fun searchWeb(
        client: OkHttpClient,
        query: String,
        maxResults: Int,
        braveApiKey: String? = null,
    ): Result<List<com.jarvis.core.agent.tools.WebTools.SearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = mutableListOf<com.jarvis.core.agent.tools.WebTools.SearchResult>()
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

                // 1. Brave Search API - reliable, no API key required for basic search
                try {
                    val braveUrl = "https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=$maxResults"
                    val braveRequest = Request.Builder()
                        .url(braveUrl)
                        .get()
                        .header("Accept", "application/json")
                        .header("X-Subscription-Token", braveApiKey ?: "")
                        .header("User-Agent", "JarvisAssistant/1.0")
                        .build()

                    client.newCall(braveRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = org.json.JSONObject(response.body?.string() ?: "")
                            json.optJSONArray("web")?.let { webArray ->
                                for (i in 0 until webArray.length()) {
                                    val item = webArray.optJSONObject(i) ?: continue
                                    val title = item.optString("title")
                                    val url = item.optString("url")
                                    val snippet = item.optString("description")
                                    if (title.isNotBlank() && url.isNotBlank()) {
                                        results.add(
                                            com.jarvis.core.agent.tools.WebTools.SearchResult(
                                                title = title,
                                                url = url,
                                                snippet = snippet,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("AgentModule", "Brave search failed: ${e.message}")
                }

                // 2. Weather fallback if query is weather-related
                val lowerQuery = query.lowercase(java.util.Locale.US)
                if (results.isEmpty() && (lowerQuery.contains("weather") || lowerQuery.contains("forecast") || lowerQuery.contains("temperature"))) {
                    try {
                        val cleanLoc = query.replace(Regex("(?i)\\b(what is the|what's the|how is the|check|get|weather in|weather for|weather today in|weather at|weather|today|tomorrow|now|current|temperature in|forecast for|forecast in)\\b"), "").trim()
                        val locParam = if (cleanLoc.isNotBlank()) java.net.URLEncoder.encode(cleanLoc, "UTF-8") else ""
                        val wttrUrl = if (locParam.isNotBlank()) "https://wttr.in/$locParam?format=3" else "https://wttr.in/?format=3"
                        val wttrRequest = Request.Builder()
                            .url(wttrUrl)
                            .get()
                            .header("User-Agent", "curl/8.0")
                            .build()
                        client.newCall(wttrRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                val weatherText = response.body?.string()?.trim().orEmpty()
                                if (weatherText.isNotBlank() && !weatherText.contains("<html") && !weatherText.contains("404")) {
                                    results.add(
                                        com.jarvis.core.agent.tools.WebTools.SearchResult(
                                            title = "Current Weather for $query",
                                            url = "https://wttr.in/$locParam",
                                            snippet = weatherText,
                                        ),
                                    )
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("AgentModule", "Weather fallback failed: ${e.message}")
                    }
                }

                // 3. DuckDuckGo Instant Answer API fallback
                if (results.isEmpty()) {
                    try {
                        val ddgUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
                        val ddgRequest = Request.Builder()
                            .url(ddgUrl)
                            .get()
                            .header("User-Agent", "JarvisAssistant/1.0")
                            .build()
                        client.newCall(ddgRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                val jsonStr = response.body?.string().orEmpty()
                                if (jsonStr.isNotBlank()) {
                                    val json = org.json.JSONObject(jsonStr)
                                    val heading = json.optString("Heading")
                                    val abstractText = json.optString("AbstractText")
                                    val abstractUrl = json.optString("AbstractURL")
                                    val answer = json.optString("Answer")
                                    if (abstractText.isNotBlank()) {
                                        results.add(
                                            com.jarvis.core.agent.tools.WebTools.SearchResult(
                                                title = heading.ifBlank { query },
                                                url = abstractUrl.ifBlank { "https://duckduckgo.com/?q=$encodedQuery" },
                                                snippet = abstractText,
                                            ),
                                        )
                                    } else if (answer.isNotBlank()) {
                                        results.add(
                                            com.jarvis.core.agent.tools.WebTools.SearchResult(
                                                title = heading.ifBlank { query },
                                                url = "https://duckduckgo.com/?q=$encodedQuery",
                                                snippet = answer,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("AgentModule", "DuckDuckGo API fallback failed: ${e.message}")
                    }
                }

                // 4. Wikipedia API fallback (reliable JSON API)
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
                        android.util.Log.e("AgentModule", "Wikipedia search fallback failed: ${e.message}")
                    }
                }

                // Return failure if no results - ensures UI shows error instead of silent failure
                if (results.isEmpty()) {
                    error("Web search returned no results for query: $query")
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

    /** Dispatches media playback query or opens music application. */
    private suspend fun playMedia(
        context: Context,
        query: String,
        appName: String?,
    ): Result<String> =
        withContext(Dispatchers.Main) {
            runCatching {
                val cleanQuery = query.trim()
                val targetApp = appName?.trim()?.lowercase()

                if (!targetApp.isNullOrBlank()) {
                    val pkg = when (targetApp) {
                        "spotify" -> "com.spotify.music"
                        "youtube", "yt" -> "com.google.android.youtube"
                        "youtube music", "yt music" -> "com.google.android.apps.youtube.music"
                        "apple music" -> "com.apple.android.music"
                        "soundcloud" -> "com.soundcloud.android"
                        else -> null
                    }
                    if (cleanQuery.isNotBlank()) {
                        val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            putExtra(SearchManager.QUERY, cleanQuery)
                            if (pkg != null) setPackage(pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (searchIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(searchIntent)
                            return@runCatching "Playing \"$cleanQuery\" on $targetApp."
                        }
                    }
                    // Fallback to launching target app
                    val launched = launchApp(context, targetApp)
                    return@runCatching if (launched.isSuccess) {
                        "Opened $targetApp for playback."
                    } else {
                        "Attempted to open $targetApp for playback."
                    }
                }

                // Default playback / search intent
                if (cleanQuery.isNotBlank()) {
                    val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        putExtra(SearchManager.QUERY, cleanQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (mediaIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(mediaIntent)
                        return@runCatching "Playing \"$cleanQuery\" on default media player."
                    }
                    // Fallback to web YouTube query
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}"),
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    return@runCatching "Searching and playing \"$cleanQuery\" on YouTube."
                } else {
                    val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)
                    val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    audioManager?.dispatchMediaKeyEvent(downEvent)
                    audioManager?.dispatchMediaKeyEvent(upEvent)
                    "Dispatched media playback resume."
                }
            }
        }

    /** Controls active media playback (play, pause, next, prev, stop). */
    private suspend fun controlMedia(
        context: Context,
        action: String,
    ): Result<String> =
        withContext(Dispatchers.Main) {
            runCatching {
                val keycode = when (action.lowercase().trim()) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keycode)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, keycode)
                audioManager?.dispatchMediaKeyEvent(downEvent)
                audioManager?.dispatchMediaKeyEvent(upEvent)
                "Media command '$action' sent."
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
                // Block reads of app/system-private data (e.g. /data/data/<pkg>/databases — the unencrypted
                // Room DB, shared_prefs, memory store). Public media lives under /sdcard|/storage, not /data.
                if (resolvedFile.absolutePath.startsWith("/data/")) {
                    error("Reading app-internal data paths is not allowed.")
                }
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
        require(trimmed.isNotEmpty()) { "File path is empty" }
        val lower = trimmed.lowercase()

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .canonicalFile
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            .canonicalFile
        val privateRoot = context.filesDir.canonicalFile

        fun childOf(root: java.io.File, child: java.io.File): Boolean =
            child.path == root.path || child.path.startsWith(root.path + java.io.File.separator)

        val candidate = when {
            lower == "/download" || lower == "download" || lower == "/downloads" || lower == "downloads" ->
                downloads
            lower.startsWith("/download/") || lower.startsWith("download/") -> {
                val sub = trimmed.substringAfter("download/").removePrefix("/")
                java.io.File(downloads, sub)
            }
            lower.startsWith("/downloads/") || lower.startsWith("downloads/") -> {
                val sub = trimmed.substringAfter("downloads/").removePrefix("/")
                java.io.File(downloads, sub)
            }
            lower == "/documents" || lower == "documents" ->
                documents
            lower.startsWith("/documents/") || lower.startsWith("documents/") -> {
                val sub = trimmed.substringAfter("documents/").removePrefix("/")
                java.io.File(documents, sub)
            }
            trimmed.startsWith("/") -> java.io.File(trimmed)
            else -> java.io.File(privateRoot, trimmed)
        }

        val canonical = candidate.canonicalFile
        if (trimmed.startsWith("/") &&
            !childOf(downloads, canonical) &&
            !childOf(documents, canonical)
        ) {
            error("Absolute paths are restricted to Downloads and Documents.")
        }
        if (!trimmed.startsWith("/") && !childOf(privateRoot, canonical) &&
            !childOf(downloads, canonical) && !childOf(documents, canonical)
        ) {
            error("Path escapes an allowed storage root.")
        }
        return canonical
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

    private fun normalizeAppQuery(input: String): String {
        return input.trim()
            .replace(Regex("""^(can you\s+)?(please\s+)?(open|launch|start|run|go to|show|find|search for)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(the|app|application)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(app|application)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(app\s+of|the\s+app)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private suspend fun listInstalledApps(
        context: Context,
        query: String?,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val activities = pm.queryIntentActivities(launcherIntent, 0)
                val appsMap = mutableMapOf<String, String>()

                for (info in activities) {
                    val label = info.loadLabel(pm).toString().trim()
                    val pkg = info.activityInfo.packageName
                    if (label.isNotEmpty() && !appsMap.containsKey(label)) {
                        appsMap[label] = pkg
                    }
                }

                val installed = runCatching { pm.getInstalledApplications(0) }.getOrNull().orEmpty()
                for (appInfo in installed) {
                    val pkg = appInfo.packageName
                    if (!appsMap.containsValue(pkg) && pm.getLaunchIntentForPackage(pkg) != null) {
                        val label = pm.getApplicationLabel(appInfo).toString().trim()
                        if (label.isNotEmpty() && !appsMap.containsKey(label)) {
                            appsMap[label] = pkg
                        }
                    }
                }

                val allApps = appsMap.entries
                    .sortedBy { it.key.lowercase(Locale.US) }
                    .map { "${it.key} (${it.value})" }

                if (query.isNullOrBlank()) {
                    allApps
                } else {
                    val cleanQuery = normalizeAppQuery(query).lowercase(Locale.US)
                    val qAlnum = cleanQuery.filter { it.isLetterOrDigit() }
                    allApps.filter { item ->
                        val lower = item.lowercase(Locale.US)
                        val itemAlnum = lower.filter { it.isLetterOrDigit() }
                        lower.contains(cleanQuery) ||
                            (qAlnum.isNotEmpty() && itemAlnum.contains(qAlnum))
                    }
                }
            }
        }

    private suspend fun launchApp(
        context: Context,
        target: String,
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val pm = context.packageManager
                val rawTarget = target.trim()
                val clean = normalizeAppQuery(rawTarget).lowercase(Locale.US)
                val cleanAlnum = clean.filter { it.isLetterOrDigit() }
                val cleanNoGoogle = clean.removePrefix("google ").trim()
                Log.d("Jarvis", "Resolving app to launch: raw=\"$target\", clean=\"$clean\"")

                // 1. Android Standard Category App Intents (guaranteed platform fallback)
                val standardCategory = when (clean) {
                    "browser", "internet", "web" -> Intent.CATEGORY_APP_BROWSER
                    "calculator", "calc" -> Intent.CATEGORY_APP_CALCULATOR
                    "calendar", "cal" -> Intent.CATEGORY_APP_CALENDAR
                    "contacts", "address book", "people" -> Intent.CATEGORY_APP_CONTACTS
                    "email", "mail" -> Intent.CATEGORY_APP_EMAIL
                    "gallery", "photos", "photo viewer" -> Intent.CATEGORY_APP_GALLERY
                    "maps", "map", "navigation" -> Intent.CATEGORY_APP_MAPS
                    "messaging", "messages", "sms", "text", "texts", "message" -> Intent.CATEGORY_APP_MESSAGING
                    "music", "audio player" -> Intent.CATEGORY_APP_MUSIC
                    else -> null
                }
                val categoryIntent = if (standardCategory != null) {
                    runCatching {
                        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, standardCategory).takeIf {
                            it.resolveActivity(pm) != null
                        }
                    }.getOrNull()
                } else null

                // 2. Direct system intents
                val directIntent = categoryIntent ?: when (clean) {
                    "camera", "take photo", "take picture" -> {
                        val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        if (camIntent.resolveActivity(pm) != null) camIntent else null
                    }
                    "phone", "dialer", "call" -> {
                        val dialIntent = Intent(Intent.ACTION_DIAL)
                        if (dialIntent.resolveActivity(pm) != null) dialIntent else null
                    }
                    "settings" -> Intent(Settings.ACTION_SETTINGS)
                    "clock", "alarm", "alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    else -> null
                }

                // 3. Direct package name lookup
                val directPackageIntent = if (rawTarget.contains(".") && !rawTarget.contains(" ")) {
                    pm.getLaunchIntentForPackage(rawTarget)
                } else null

                // 4. Query Launcher Activities
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val activities = pm.queryIntentActivities(launcherIntent, 0)
                Log.d("Jarvis", "Found ${activities.size} launcher activities on device")

                val matchedActivity = activities.firstOrNull {
                    val label = it.loadLabel(pm).toString()
                    label.equals(clean, ignoreCase = true) || (cleanNoGoogle.isNotEmpty() && label.equals(cleanNoGoogle, ignoreCase = true))
                } ?: activities.firstOrNull {
                    val label = it.loadLabel(pm).toString()
                    val labelAlnum = label.filter { ch -> ch.isLetterOrDigit() }
                    cleanAlnum.isNotEmpty() && labelAlnum.equals(cleanAlnum, ignoreCase = true)
                } ?: activities.firstOrNull {
                    val pkg = it.activityInfo.packageName
                    pkg.equals(clean, ignoreCase = true) || pkg.endsWith(".$clean", ignoreCase = true)
                } ?: activities.firstOrNull {
                    val label = it.loadLabel(pm).toString()
                    label.contains(clean, ignoreCase = true)
                } ?: activities.firstOrNull {
                    val label = it.loadLabel(pm).toString()
                    cleanNoGoogle.isNotEmpty() && label.contains(cleanNoGoogle, ignoreCase = true)
                } ?: activities.firstOrNull {
                    val pkg = it.activityInfo.packageName
                    pkg.contains(clean, ignoreCase = true)
                }

                val activityIntent = matchedActivity?.let { act ->
                    val pkg = act.activityInfo.packageName
                    pm.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(pkg, act.activityInfo.name)
                    }
                }

                // 5. Query all installed applications (supplemental)
                val installedAppIntent = if (directIntent == null && directPackageIntent == null && activityIntent == null) {
                    val installed = runCatching { pm.getInstalledApplications(0) }.getOrNull().orEmpty()
                    val matchedApp = installed.firstOrNull {
                        val label = pm.getApplicationLabel(it).toString()
                        label.equals(clean, ignoreCase = true) || (cleanNoGoogle.isNotEmpty() && label.equals(cleanNoGoogle, ignoreCase = true))
                    } ?: installed.firstOrNull {
                        val label = pm.getApplicationLabel(it).toString()
                        val labelAlnum = label.filter { ch -> ch.isLetterOrDigit() }
                        cleanAlnum.isNotEmpty() && labelAlnum.equals(cleanAlnum, ignoreCase = true)
                    } ?: installed.firstOrNull {
                        it.packageName.equals(clean, ignoreCase = true) || it.packageName.endsWith(".$clean", ignoreCase = true)
                    } ?: installed.firstOrNull {
                        val label = pm.getApplicationLabel(it).toString()
                        label.contains(clean, ignoreCase = true)
                    } ?: installed.firstOrNull {
                        it.packageName.contains(clean, ignoreCase = true)
                    }
                    matchedApp?.let { pm.getLaunchIntentForPackage(it.packageName) }
                } else null

                // 6. Known popular app package fallback (handles standard OEM/Google packages)
                val popularFallbackIntent = if (directIntent == null && directPackageIntent == null && activityIntent == null && installedAppIntent == null) {
                    val candidatePackages = when {
                        clean.contains("youtube") -> listOf("com.google.android.youtube")
                        clean.contains("whatsapp") || clean == "wa" -> listOf("com.whatsapp", "com.whatsapp.w4b")
                        clean.contains("instagram") || clean == "insta" -> listOf("com.instagram.android")
                        clean.contains("telegram") -> listOf("org.telegram.messenger")
                        clean.contains("spotify") -> listOf("com.spotify.music")
                        clean.contains("facebook") || clean == "fb" -> listOf("com.facebook.katana", "com.facebook.lite")
                        clean.contains("twitter") || clean == "x" -> listOf("com.twitter.android")
                        clean.contains("chrome") || clean.contains("browser") -> listOf("com.android.chrome", "com.google.android.apps.chrome")
                        clean.contains("maps") -> listOf("com.google.android.apps.maps")
                        clean.contains("gmail") || clean.contains("email") || clean.contains("mail") -> listOf("com.google.android.gm")
                        clean.contains("photos") || clean.contains("gallery") -> listOf("com.google.android.apps.photos", "com.android.gallery3d")
                        clean.contains("calendar") -> listOf("com.google.android.calendar", "com.android.calendar")
                        clean.contains("play store") || clean.contains("google play") || clean.contains("playstore") -> listOf("com.android.vending")
                        clean.contains("clock") -> listOf("com.google.android.deskclock", "com.android.deskclock")
                        clean.contains("calculator") -> listOf("com.google.android.calculator", "com.android.calculator2")
                        clean.contains("camera") -> listOf("com.google.android.GoogleCamera", "com.android.camera2", "com.android.camera")
                        clean.contains("files") || clean.contains("file manager") -> listOf("com.google.android.apps.nbu.files", "com.android.documentsui")
                        clean.contains("netflix") -> listOf("com.netflix.mediaclient")
                        clean.contains("reddit") -> listOf("com.reddit.frontpage")
                        clean.contains("discord") -> listOf("com.discord")
                        clean.contains("slack") -> listOf("com.Slack")
                        clean.contains("zoom") -> listOf("us.zoom.videomeetings")
                        else -> emptyList()
                    }
                    candidatePackages.firstNotNullOfOrNull { pkg -> pm.getLaunchIntentForPackage(pkg) }
                } else null

                val finalIntent = directIntent
                    ?: directPackageIntent
                    ?: activityIntent
                    ?: installedAppIntent
                    ?: popularFallbackIntent
                    ?: error("Could not find installed application matching \"$target\"")

                finalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Log.d("Jarvis", "Launching app intent: $finalIntent")
                context.startActivity(finalIntent)
            }
        }

    private suspend fun sendAppMessageIntent(
        context: Context,
        appName: String,
        recipient: String,
        message: String,
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val clean = appName.lowercase().trim()
                when (clean) {
                    "whatsapp", "wa" -> {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message)
                            setPackage("com.whatsapp")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (sendIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(sendIntent)
                        } else {
                            val uri = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
                            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(viewIntent)
                        }
                    }
                    "telegram" -> {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message)
                            setPackage("org.telegram.messenger")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (sendIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(sendIntent)
                        } else {
                            val uri = Uri.parse("https://t.me/share/url?url=&text=${Uri.encode(message)}")
                            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(viewIntent)
                        }
                    }
                    else -> {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val chooser = Intent.createChooser(sendIntent, "Send via $appName").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    }
                }
            }
        }

    private suspend fun searchAppIntent(
        context: Context,
        appName: String,
        query: String,
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val clean = normalizeAppQuery(appName).lowercase(Locale.US).trim()
                when {
                    clean.contains("play store") || clean.contains("playstore") || clean.contains("google play") || clean == "market" || clean == "store" -> {
                        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}&c=apps")).apply {
                            setPackage("com.android.vending")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (searchIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(searchIntent)
                        } else {
                            val anyMarketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}&c=apps")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (anyMarketIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(anyMarketIntent)
                            } else {
                                val webStoreIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(webStoreIntent)
                            }
                        }
                    }
                    clean == "youtube" || clean.contains("youtube") -> {
                        val intent = Intent(Intent.ACTION_SEARCH).apply {
                            setPackage("com.google.android.youtube")
                            putExtra(SearchManager.QUERY, query)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            val webIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(webIntent)
                        }
                    }
                    clean == "instagram" || clean == "insta" || clean.contains("instagram") -> {
                        val uri = Uri.parse("https://www.instagram.com/explore/tags/${Uri.encode(query)}/")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            setPackage("com.instagram.android")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            val webIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.instagram.com/explore/tags/${Uri.encode(query)}/")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(webIntent)
                        }
                    }
                    clean == "spotify" || clean.contains("spotify") -> {
                        val uri = Uri.parse("spotify:search:${Uri.encode(query)}")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            setPackage("com.spotify.music")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            val webIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(webIntent)
                        }
                    }
                    clean == "maps" || clean.contains("maps") -> {
                        val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
                        val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    else -> {
                        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, "$clean $query")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (webSearchIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(webSearchIntent)
                        } else {
                            val webIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q=${Uri.encode("$clean $query")}")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(webIntent)
                        }
                    }
                }
            }
        }

    private suspend fun installAppFromStore(
        context: Context,
        appName: String,
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val clean = normalizeAppQuery(appName).lowercase(Locale.US).trim()
                val knownPackage = when {
                    clean == "youtube" || clean.contains("youtube") -> "com.google.android.youtube"
                    clean == "whatsapp" || clean.contains("whatsapp") -> "com.whatsapp"
                    clean == "instagram" || clean.contains("instagram") -> "com.instagram.android"
                    clean == "telegram" || clean.contains("telegram") -> "org.telegram.messenger"
                    clean == "spotify" || clean.contains("spotify") -> "com.spotify.music"
                    clean == "facebook" || clean == "fb" || clean.contains("facebook") -> "com.facebook.katana"
                    clean == "twitter" || clean == "x" -> "com.twitter.android"
                    clean == "chrome" || clean.contains("chrome") -> "com.android.chrome"
                    clean == "netflix" || clean.contains("netflix") -> "com.netflix.mediaclient"
                    clean == "reddit" || clean.contains("reddit") -> "com.reddit.frontpage"
                    clean == "discord" || clean.contains("discord") -> "com.discord"
                    clean == "slack" || clean.contains("slack") -> "com.Slack"
                    clean == "zoom" || clean.contains("zoom") -> "us.zoom.videomeetings"
                    else -> null
                }

                val marketDetailsIntent = if (knownPackage != null) {
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$knownPackage")).apply {
                        setPackage("com.android.vending")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else null

                val detailsResolved = marketDetailsIntent?.takeIf { it.resolveActivity(context.packageManager) != null }

                val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(clean)}&c=apps")).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val finalIntent = detailsResolved
                    ?: searchIntent.takeIf { it.resolveActivity(context.packageManager) != null }
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(clean)}&c=apps")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }.takeIf { it.resolveActivity(context.packageManager) != null }
                    ?: Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(if (knownPackage != null) "https://play.google.com/store/apps/details?id=$knownPackage" else "https://play.google.com/store/search?q=${Uri.encode(clean)}&c=apps")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

                context.startActivity(finalIntent)
            }
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

    private suspend fun setFlashlight(
        context: Context,
        enabled: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    ?: error("Camera manager is unavailable on this device")
                val cameraIds = cameraManager.cameraIdList
                if (cameraIds.isEmpty()) {
                    error("No camera found on this device")
                }
                val torchCameraId = cameraIds.firstOrNull { id ->
                    runCatching {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                        val isBackFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                        hasFlash && isBackFacing
                    }.getOrDefault(false)
                } ?: cameraIds.firstOrNull { id ->
                    runCatching {
                        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }.getOrDefault(false)
                } ?: cameraIds.firstOrNull()
                  ?: error("No camera with flashlight/torch capability found on this device")

                cameraManager.setTorchMode(torchCameraId, enabled)
            }
        }

    private suspend fun toggleBluetooth(
        context: Context,
        enabled: Boolean,
    ): Result<String> =
        withContext(Dispatchers.Main) {
            runCatching {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter ?: error("Bluetooth is unavailable on this device")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opened Bluetooth settings to turn Bluetooth ${if (enabled) "on" else "off"}."
                } else {
                    if (enabled) {
                        if (adapter.isEnabled) {
                            "Bluetooth is already on."
                        } else {
                            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            "Prompted system dialog to enable Bluetooth."
                        }
                    } else {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        "Opened Bluetooth settings to turn Bluetooth off."
                    }
                }
            }
        }

    private suspend fun openSettings(
        context: Context,
        settingType: String,
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val action = when (settingType.lowercase().trim()) {
                    "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "wifi", "wi-fi", "internet", "network" -> Settings.ACTION_WIFI_SETTINGS
                    "display", "screen", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound", "volume", "audio" -> Settings.ACTION_SOUND_SETTINGS
                    "battery", "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
                    "security", "privacy" -> Settings.ACTION_SECURITY_SETTINGS
                    "date", "time" -> Settings.ACTION_DATE_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                val intent = Intent(action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
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
