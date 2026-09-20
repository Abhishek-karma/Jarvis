package com.jarvis.core.capability

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Notification capabilities - read notifications, react to notification actions
 */
class NotificationCapability(
    private val context: Context,
) : Capability {

    override val id = CapabilityIds.NOTIFICATIONS_READ

    override val description = "Read and interact with notifications (requires NotificationListener permission)"

    override val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        isNotificationListenerEnabled()
    }

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: list_notifications, get_active",
        )

        return when (action) {
            "list_notifications" -> listNotifications(request)
            "get_active" -> getActiveNotifications(request)
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown notification action: $action",
            )
        }
    }

    private suspend fun listNotifications(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        if (!isNotificationListenerEnabled()) {
            return@withContext CapabilityResult.Unavailable(
                reason = "Notification listener not enabled. Enable in Settings > Notifications > Notification access",
                missingPermissions = requiredPermissions,
            )
        }

        CapabilityResult.Success(
            output = "Notification reading requires a NotificationListenerService implementation",
            structuredData = mapOf("requires_service" to true),
        )
    }

    private suspend fun getActiveNotifications(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        listNotifications(request)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: ""
        return enabledListeners.contains(context.packageName)
    }
}