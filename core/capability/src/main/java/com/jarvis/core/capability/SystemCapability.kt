package com.jarvis.core.capability

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * System capabilities - intents, system settings, time
 */
class SystemCapability(
    private val context: Context,
) : Capability {

    override val id = CapabilityIds.SYSTEM_INTENTS

    override val description = "Execute system intents, query system settings, and device information"

    override val requiredPermissions = emptyList<String>()

    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: open_settings, send_intent, get_device_info, set_alarm, set_timer",
        )

        return when (action) {
            "open_settings" -> openSettings(request)
            "send_intent" -> sendIntent(request)
            "get_device_info" -> getDeviceInfo(request)
            "set_alarm" -> setAlarm(request)
            "set_timer" -> setTimer(request)
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown system action: $action",
            )
        }
    }

    private suspend fun openSettings(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.Main) {
        val settingsAction = request.parameters["settings_action"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'settings_action' parameter"
        )

        try {
            val intent = Intent(settingsAction)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CapabilityResult.Success(
                output = "Opened settings: $settingsAction",
                structuredData = mapOf("action" to settingsAction),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("SETTINGS_ERROR", "Failed to open settings: ${e.message}")
        }
    }

    private suspend fun sendIntent(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.Main) {
        val action = request.parameters["intent_action"] as? String ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'intent_action' parameter"
        )
        val packageName = request.parameters["package"] as? String
        val data = request.parameters["data"] as? String

        try {
            val intent = Intent(action)
            packageName?.let { intent.setPackage(it) }
            data?.let { intent.setData(Uri.parse(it)) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CapabilityResult.Success(
                output = "Sent intent: $action",
                structuredData = mapOf<String, Any>(
                    "action" to action,
                    "package" to (packageName ?: ""),
                    "data" to (data ?: ""),
                ),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("INTENT_ERROR", "Failed to send intent: ${e.message}")
        }
    }

    private suspend fun getDeviceInfo(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val info: Map<String, Any> = mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android_version" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "brand" to Build.BRAND,
        )
        CapabilityResult.Success(
            output = "Device: ${info["manufacturer"]} ${info["model"]} (Android ${info["android_version"]}, API ${info["sdk_int"]})",
            structuredData = info,
        )
    }

    private suspend fun setAlarm(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
        val triggerAt = request.parameters["trigger_at"] as? Long ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'trigger_at' (millis UTC)"
        )
        val label = request.parameters["label"] as? String ?: "Alarm"

        try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                ?: return@withContext CapabilityResult.Failure("UNAVAILABLE", "Alarm service unavailable")
            val alarmIntent = Intent("com.jarvis.action.AGENT_ALARM").apply {
                setPackage(context.packageName)
                putExtra("label", label)
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                label.hashCode(),
                alarmIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            } else {
                manager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
            CapabilityResult.Success(
                output = "Alarm set for ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(triggerAt))}: $label",
                structuredData = mapOf("trigger_at" to triggerAt, "label" to label),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("ALARM_ERROR", "Failed to set alarm: ${e.message}")
        }
    }

    private suspend fun setTimer(request: CapabilityRequest): CapabilityResult = withContext(Dispatchers.Main) {
        val durationSec = request.parameters["duration_sec"] as? Int ?: return@withContext CapabilityResult.Failure(
            code = "MISSING_PARAMETER", message = "Missing 'duration_sec' parameter"
        )
        val label = request.parameters["label"] as? String ?: "Timer"

        try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, durationSec)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            CapabilityResult.Success(
                output = "Timer set for $durationSec seconds: $label",
                structuredData = mapOf("duration_sec" to durationSec, "label" to label),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("TIMER_ERROR", "Failed to set timer: ${e.message}")
        }
    }
}