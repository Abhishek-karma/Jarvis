package com.jarvis.core.capability

import kotlinx.coroutines.CancellationException

/**
 * A capability represents something Jarvis can actually accomplish.
 * Capabilities are declarative — they describe WHAT can be done, not HOW.
 * The execution strategy (HOW) is resolved separately by the GoalEngine.
 */
sealed interface Capability {
    /** Unique capability identifier, e.g., "device.battery", "communication.send_sms" */
    val id: String

    /** Human-readable description of what this capability does */
    val description: String

    /** Required Android permissions for this capability */
    val requiredPermissions: List<String>

    /** Whether this capability is available on the current device/context */
    suspend fun isAvailable(): Boolean

    /** Execute this capability with the given request */
    suspend fun execute(request: CapabilityRequest): CapabilityResult
}

/** Input request for a capability execution */
data class CapabilityRequest(
    val goalDescription: String,
    val parameters: Map<String, Any> = emptyMap(),
    val context: Map<String, Any> = emptyMap(),
    val idempotencyKey: String? = null,
)

/** Result of a capability execution */
sealed class CapabilityResult {
    data class Success(
        val output: String,
        val structuredData: Map<String, Any>? = null,
        val verificationHint: VerificationHint? = null,
    ) : CapabilityResult()

    data class Failure(
        val code: String,
        val message: String,
        val isRetryable: Boolean = false,
    ) : CapabilityResult()

    data class Unavailable(
        val reason: String,
        val missingPermissions: List<String> = emptyList(),
    ) : CapabilityResult()

    data class ConfirmationRequired(
        val toolName: String,
        val argsJson: String,
        val tier: PermissionTier,
    ) : CapabilityResult()

    data class Cancelled(
        val reason: String = "Cancelled by user or system",
    ) : CapabilityResult()

    data class Unknown(
        val reason: String,
    ) : CapabilityResult()
}

/** Hint for verification after execution */
data class VerificationHint(
    val verificationType: String,
    val expectedState: Map<String, Any> = emptyMap(),
)

/** Permission tier for safety gating */
enum class PermissionTier {
    READ_ONLY,
    REVERSIBLE_WRITE,
    SENSITIVE,
}

/** Predefined capability IDs for type safety */
object CapabilityIds {
    // Device capabilities
    const val BATTERY = "device.battery"
    const val WIFI = "device.wifi"
    const val BLUETOOTH = "device.bluetooth"
    const val VOLUME = "device.volume"
    const val FLASHLIGHT = "device.flashlight"
    const val DEVICE_STATE = "device.state"
    const val STORAGE = "device.storage"
    const val NETWORK = "device.network"
    const val TIME = "device.time"

    // App capabilities
    const val LAUNCH_APP = "app.launch"
    const val APP_INTERACTION = "app.interaction"

    // Communication capabilities
    const val CONTACTS = "communication.contacts"
    const val PHONE_CALLS = "communication.phone_calls"
    const val MESSAGES = "communication.messages"

    // Calendar capabilities
    const val CALENDAR_EVENTS = "calendar.events"
    const val CALENDAR_REMINDERS = "calendar.reminders"
    const val CALENDAR_SCHEDULES = "calendar.schedules"

    // File capabilities
    const val FILES_READ = "files.read"
    const val FILES_WRITE = "files.write"
    const val FILES_SEARCH = "files.search"
    const val FILES_DELETE = "files.delete"

    // Media capabilities
    const val MEDIA_PLAYBACK = "media.playback"
    const val MEDIA_CONTROLS = "media.controls"

    // Web capabilities
    const val WEB_SEARCH = "web.search"
    const val WEB_BROWSE = "web.browse"
    const val WEB_FETCH = "web.fetch"

    // Notification capabilities
    const val NOTIFICATIONS_READ = "notifications.read"
    const val NOTIFICATIONS_ACTION = "notifications.action"

    // Automation capabilities
    const val UI_AUTOMATION = "automation.ui"

    // System capabilities
    const val SYSTEM_INTENTS = "system.intents"
    const val SYSTEM_SETTINGS = "system.settings"
    const val SYSTEM_TIME = "system.time"
}