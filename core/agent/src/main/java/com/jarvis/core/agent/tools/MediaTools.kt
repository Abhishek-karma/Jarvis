package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult

/**
 * Media/device-control tools (v0.5 catalog, `06-AGENT §3`): adjust playback volume.
 * Reversible-write tier — volume changes are instantly user-visible and equally
 * user-reversible. The AudioManager call is injected as a lambda returning a
 * human-readable post-state (e.g. "media volume 7/15") so the tool is JVM-unit-testable.
 */
object MediaTools {
    const val ADJUST_VOLUME = "adjust_volume"

    const val ACTION_UP = "up"
    const val ACTION_DOWN = "down"
    const val ACTION_MUTE = "mute"
    const val ACTION_UNMUTE = "unmute"

    val manifestNames: List<String> = listOf(ADJUST_VOLUME)

    fun all(
        adjust: suspend (action: String, stream: String) -> Result<String>,
    ): List<Tool> = listOf(adjustVolume(adjust))

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

    internal const val STREAM_MEDIA = "media"

    internal val VALID_ACTIONS = listOf(ACTION_UP, ACTION_DOWN, ACTION_MUTE, ACTION_UNMUTE)
    internal val VALID_STREAMS = listOf(STREAM_MEDIA, "ring", "alarm")

    private const val VOLUME_SCHEMA =
        """{"type":"object","properties":{"action":{"type":"string","enum":["up","down","mute","unmute"]},"stream":{"type":"string","enum":["media","ring","alarm"]}},"required":["action"]}"""
}
