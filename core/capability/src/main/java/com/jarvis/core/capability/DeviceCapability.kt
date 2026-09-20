package com.jarvis.core.capability

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device capabilities - battery, WiFi, Bluetooth, volume, flashlight, device state, storage
 */
class DeviceCapability(
    private val context: Context,
) : Capability {

    override val id = CapabilityIds.DEVICE_STATE

    override val description = "Query and control device state (battery, storage, network, clipboard, settings)"

    override val requiredPermissions = emptyList<String>()

    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: battery, storage, network, clipboard, volume, time",
        )

        return when (action) {
            "battery" -> getBatteryStatus()
            "storage" -> getStorageInfo()
            "network" -> getNetworkState()
            "clipboard" -> getClipboard()
            "volume" -> getVolume()
            "time" -> getCurrentTime()
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown device action: $action",
            )
        }
    }

    private suspend fun getBatteryStatus(): CapabilityResult = withContext(Dispatchers.IO) {
        try {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return@withContext CapabilityResult.Failure(
                code = "UNAVAILABLE",
                message = "Battery manager unavailable",
            )
            val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 } ?: 0
            val isCharging = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
            val healthStr = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }

            CapabilityResult.Success(
                output = "Battery: $level%${if (isCharging) " (charging)" else ""}, Health: $healthStr",
                structuredData = mapOf<String, Any>(
                    "level" to level,
                    "charging" to isCharging,
                    "health" to healthStr,
                ),
                verificationHint = VerificationHint("device.battery", mapOf<String, Any>("level" to level))
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("BATTERY_ERROR", "Failed to read battery: ${e.message}")
        }
    }

    private suspend fun getStorageInfo(): CapabilityResult = withContext(Dispatchers.IO) {
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val totalBytes = stat.totalBytes
            val freeBytes = stat.availableBytes
            val usedBytes = totalBytes - freeBytes
            val percentUsed = (usedBytes * 100f / totalBytes).roundToInt()

            CapabilityResult.Success(
                output = "Storage: ${formatBytes(freeBytes)} free of ${formatBytes(totalBytes)} ($percentUsed% used)",
                structuredData = mapOf(
                    "total_bytes" to totalBytes,
                    "free_bytes" to freeBytes,
                    "used_bytes" to usedBytes,
                    "percent_used" to percentUsed,
                ),
                verificationHint = VerificationHint("device.storage", mapOf("free_bytes" to freeBytes))
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("STORAGE_ERROR", "Failed to read storage: ${e.message}")
        }
    }

    private suspend fun getNetworkState(): CapabilityResult = withContext(Dispatchers.IO) {
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@withContext CapabilityResult.Failure(
                code = "UNAVAILABLE",
                message = "Connectivity manager unavailable",
            )
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@withContext CapabilityResult.Success(
                output = "Network: offline",
                structuredData = mapOf("online" to false),
            )
            val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }

            CapabilityResult.Success(
                output = "Network: ${if (online) "online ($transport)" else "offline"}",
                structuredData = mapOf(
                    "online" to online,
                    "transport" to transport,
                ),
                verificationHint = VerificationHint("device.network", mapOf("online" to online))
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("NETWORK_ERROR", "Failed to read network: ${e.message}")
        }
    }

    private suspend fun getClipboard(): CapabilityResult = withContext(Dispatchers.Main) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString() ?: "empty"
            } else {
                "empty"
            }

            CapabilityResult.Success(
                output = "Clipboard: ${text.take(100)}${if (text.length > 100) "..." else ""}",
                structuredData = mapOf("content" to text),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("CLIPBOARD_ERROR", "Failed to read clipboard: ${e.message}")
        }
    }

    private suspend fun getVolume(): CapabilityResult = withContext(Dispatchers.IO) {
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return@withContext CapabilityResult.Failure(
                code = "UNAVAILABLE",
                message = "Audio manager unavailable",
            )
            val musicVol = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val musicMax = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val ringVol = manager.getStreamVolume(android.media.AudioManager.STREAM_RING)
            val ringMax = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
            val alarmVol = manager.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            val alarmMax = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)

            CapabilityResult.Success(
                output = "Volume - Media: $musicVol/$musicMax, Ring: $ringVol/$ringMax, Alarm: $alarmVol/$alarmMax",
                structuredData = mapOf(
                    "music" to mapOf("current" to musicVol, "max" to musicMax),
                    "ring" to mapOf("current" to ringVol, "max" to ringMax),
                    "alarm" to mapOf("current" to alarmVol, "max" to alarmMax),
                ),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("VOLUME_ERROR", "Failed to read volume: ${e.message}")
        }
    }

    private suspend fun getCurrentTime(): CapabilityResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault())
        CapabilityResult.Success(
            output = "Current time: ${dateFormat.format(java.util.Date(now))}",
            structuredData = mapOf("timestamp" to now, "timezone" to java.util.TimeZone.getDefault().id),
        )
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= (1L shl 30) -> "${bytes / (1L shl 30)} GB"
            bytes >= (1L shl 20) -> "${bytes / (1L shl 20)} MB"
            bytes >= (1L shl 10) -> "${bytes / (1L shl 10)} KB"
            else -> "$bytes B"
        }
    }
}