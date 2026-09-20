package com.jarvis.core.capability

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Media capabilities - playback, volume, media controls
 */
class MediaCapability(
    private val context: Context,
) : Capability {

    override val id = CapabilityIds.MEDIA_PLAYBACK

    override val description = "Control media playback and volume"

    override val requiredPermissions = emptyList<String>()

    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val action = request.parameters["action"] as? String ?: return CapabilityResult.Failure(
            code = "INVALID_PARAMETER",
            message = "Missing 'action' parameter. Available: play, pause, next, previous, volume_up, volume_down, volume_mute, volume_unmute",
        )

        return when (action) {
            "play" -> mediaControl("play")
            "pause" -> mediaControl("pause")
            "next" -> mediaControl("next")
            "previous" -> mediaControl("previous")
            "volume_up" -> adjustVolume("up")
            "volume_down" -> adjustVolume("down")
            "volume_mute" -> adjustVolume("mute")
            "volume_unmute" -> adjustVolume("unmute")
            else -> CapabilityResult.Failure(
                code = "UNKNOWN_ACTION",
                message = "Unknown media action: $action",
            )
        }
    }

    private suspend fun mediaControl(action: String): CapabilityResult = withContext(Dispatchers.Main) {
        try {
            val intent = Intent("com.android.music.musicservicecommand").apply {
                putExtra("command", action)
            }
            context.sendBroadcast(intent)
            CapabilityResult.Success(
                output = "Media $action command sent",
                structuredData = mapOf("action" to action),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("MEDIA_ERROR", "Failed to control media: ${e.message}")
        }
    }

    private suspend fun adjustVolume(action: String): CapabilityResult = withContext(Dispatchers.IO) {
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@withContext CapabilityResult.Failure("UNAVAILABLE", "Audio manager unavailable")
            val stream = AudioManager.STREAM_MUSIC
            when (action) {
                "up" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                "down" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
                "mute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                "unmute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            }
            val current = manager.getStreamVolume(stream)
            val max = manager.getStreamMaxVolume(stream)
            CapabilityResult.Success(
                output = "Media volume $action: $current/$max",
                structuredData = mapOf("current" to current, "max" to max, "action" to action),
            )
        } catch (e: Exception) {
            CapabilityResult.Failure("VOLUME_ERROR", "Failed to adjust volume: ${e.message}")
        }
    }
}