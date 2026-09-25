package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier

object MediaTools {
    const val ADJUST_VOLUME = "adjust_volume"
    const val PLAY_MEDIA = "play_media"
    const val MEDIA_CONTROL = "media_control"

    const val ACTION_UP = "up"
    const val ACTION_DOWN = "down"
    const val ACTION_MUTE = "mute"
    const val ACTION_UNMUTE = "unmute"

    val manifestNames: List<String> = listOf(ADJUST_VOLUME, PLAY_MEDIA, MEDIA_CONTROL)

    fun all(
        adjust: suspend (action: String, stream: String) -> Result<String>,
        play: suspend (query: String, appName: String?) -> Result<String> = { q, app ->
            Result.success("Playing \"$q\"${if (!app.isNullOrBlank()) " on $app" else ""}.")
        },
        control: suspend (action: String) -> Result<String> = { act ->
            Result.success("Media command '$act' dispatched.")
        },
    ): List<Tool> = listOf(
        adjustVolume(adjust),
        playMedia(play),
        mediaControl(control),
    )

    fun adjustVolume(adjust: suspend (action: String, stream: String) -> Result<String>): Tool =
        object : Tool {
            override val name = ADJUST_VOLUME
            override val description =
                "Adjust device volume: nudge it up or down, or mute/unmute. " +
                    "Streams: media (default), ring, alarm."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = VOLUME_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val action = args.string("action")?.trim()?.lowercase()
                if (action == null || action !in VALID_ACTIONS) {
                    return ToolResult(
                        success = false,
                        observationText =
                            "Unknown action \"${action ?: ""}\" — use one of $VALID_ACTIONS.",
                        error = "action must be one of $VALID_ACTIONS",
                    )
                }
                val stream = args.string("stream")?.trim()?.lowercase().orEmpty().ifBlank { STREAM_MEDIA }
                if (stream !in VALID_STREAMS) {
                    return ToolResult(
                        success = false,
                        observationText =
                            "Unknown stream \"${stream}\" — use one of $VALID_STREAMS.",
                        error = "stream must be one of $VALID_STREAMS",
                    )
                }
                return adjust(action, stream).fold(
                    onSuccess = { postState ->
                        ToolResult(
                            success = true,
                            observationText = "Volume adjusted: $postState.",
                            structuredData = mapOf("action" to action, "stream" to stream),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not adjust volume.",
                            error = error.message ?: "Volume adjust failed",
                        )
                    },
                )
            }
        }

    fun playMedia(play: suspend (query: String, appName: String?) -> Result<String>): Tool =
        object : Tool {
            override val name = PLAY_MEDIA
            override val description =
                "Play music, podcasts, or media by search query/artist/track or open a music app " +
                    "(e.g. Spotify, YouTube Music, Apple Music). Action tier."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = PLAY_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val query = args?.string("query")?.trim().orEmpty()
                val appName = args?.string("app_name")?.trim()

                return play(query, appName).fold(
                    onSuccess = { outcome ->
                        ToolResult(
                            success = true,
                            observationText = outcome,
                            structuredData = mapOf("query" to query, "app_name" to (appName ?: "default")),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Failed to play media: ${error.message}",
                            error = error.message ?: "Play media failed",
                        )
                    },
                )
            }
        }

    fun mediaControl(control: suspend (action: String) -> Result<String>): Tool =
        object : Tool {
            override val name = MEDIA_CONTROL
            override val description =
                "Control active media playback: play, pause, toggle, next track, previous track, or stop. Action tier."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = CONTROL_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val action = args?.string("action")?.trim()?.lowercase().orEmpty().ifBlank { "toggle" }
                if (action !in VALID_CONTROLS) {
                    return ToolResult(
                        success = false,
                        observationText = "Invalid media control action \"$action\". Valid actions: $VALID_CONTROLS",
                        error = "invalid_action",
                    )
                }
                return control(action).fold(
                    onSuccess = { outcome ->
                        ToolResult(
                            success = true,
                            observationText = outcome,
                            structuredData = mapOf("action" to action),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Failed to send media control: ${error.message}",
                            error = error.message ?: "Media control failed",
                        )
                    },
                )
            }
        }

    internal const val STREAM_MEDIA = "media"

    internal val VALID_ACTIONS = listOf(ACTION_UP, ACTION_DOWN, ACTION_MUTE, ACTION_UNMUTE)
    internal val VALID_STREAMS = listOf(STREAM_MEDIA, "ring", "alarm")
    internal val VALID_CONTROLS = listOf("play", "pause", "toggle", "next", "previous", "stop")

    private const val VOLUME_SCHEMA =
        """{"type":"object","properties":{"action":{"type":"string","enum":["up","down","mute","unmute"]},"stream":{"type":"string","enum":["media","ring","alarm"]}},"required":["action"]}"""

    private const val PLAY_SCHEMA =
        """{"type":"object","properties":{"query":{"type":"string","description":"Song, artist, genre, playlist, or topic to play (e.g. 'jazz', 'lofi', 'The Beatles')"},"app_name":{"type":"string","description":"Target media app (e.g. 'spotify', 'youtube', 'youtube music', 'apple music')"}}}"""

    private const val CONTROL_SCHEMA =
        """{"type":"object","properties":{"action":{"type":"string","enum":["play","pause","toggle","next","previous","stop"],"description":"Playback control command"}},"required":["action"]}"""
}
