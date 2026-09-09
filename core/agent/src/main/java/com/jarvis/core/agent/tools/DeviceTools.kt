package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier

object DeviceTools {
    const val LAUNCH_APP = "launch_app"
    const val COPY_TO_CLIPBOARD = "copy_to_clipboard"
    const val READ_CLIPBOARD = "read_clipboard"
    const val SHOW_NOTIFICATION = "show_notification"

    val manifestNames: List<String> = listOf(
        LAUNCH_APP,
        COPY_TO_CLIPBOARD,
        READ_CLIPBOARD,
        SHOW_NOTIFICATION,
    )

    fun all(
        launchApp: (suspend (packageNameOrName: String) -> Result<Unit>)? = null,
        copyClipboard: (suspend (text: String, label: String?) -> Result<Unit>)? = null,
        readClipboard: (suspend () -> Result<String?>)? = null,
        showNotification: (suspend (title: String, message: String) -> Result<Unit>)? = null,
    ): List<Tool> = buildList {
        if (launchApp != null) add(launchApp(launchApp))
        if (copyClipboard != null) add(copyToClipboard(copyClipboard))
        if (readClipboard != null) add(readClipboard(readClipboard))
        if (showNotification != null) add(showNotification(showNotification))
    }

    fun launchApp(launch: suspend (String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = LAUNCH_APP
            override val description =
                "Launch an application on the user's device given its package name or app name. Action tier."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = LAUNCH_APP_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val target = args.string("package_name")
                    ?: args.string("packageName")
                    ?: args.string("app_name")
                    ?: args.string("appName")
                    ?: args.string("target")
                    ?: args.string("app")
                if (target.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing package_name or app_name argument.",
                        error = "package_name or app_name is required",
                    )
                }
                return launch(target.trim()).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Launched \"$target\".",
                            structuredData = mapOf("target" to target),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not launch \"$target\".",
                            error = error.message ?: "App launch failed",
                        )
                    },
                )
            }
        }

    fun copyToClipboard(copy: suspend (String, String?) -> Result<Unit>): Tool =
        object : Tool {
            override val name = COPY_TO_CLIPBOARD
            override val description =
                "Copy text to the system clipboard with an optional label. Reversible write."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = COPY_CLIPBOARD_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val text = args.string("text") ?: args.string("content")
                val label = args.string("label")
                if (text == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: text is required.",
                        error = "text is required",
                    )
                }
                return copy(text, label).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Copied ${text.length} characters to clipboard.",
                            structuredData = mapOf("length" to text.length),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not copy text to clipboard.",
                            error = error.message ?: "Clipboard copy failed",
                        )
                    },
                )
            }
        }

    fun readClipboard(read: suspend () -> Result<String?>): Tool =
        object : Tool {
            override val name = READ_CLIPBOARD
            override val description =
                "Read the current text from the system clipboard. Read-only."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""

            override suspend fun execute(argsJson: String): ToolResult {
                return read().fold(
                    onSuccess = { text ->
                        if (text.isNullOrBlank()) {
                            ToolResult(
                                success = true,
                                observationText = "Clipboard is empty.",
                                structuredData = mapOf("empty" to true),
                            )
                        } else {
                            ToolResult(
                                success = true,
                                observationText = "Clipboard content:\n$text",
                                structuredData = mapOf("length" to text.length),
                            )
                        }
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not read clipboard.",
                            error = error.message ?: "Clipboard read failed",
                        )
                    },
                )
            }
        }

    fun showNotification(show: suspend (String, String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = SHOW_NOTIFICATION
            override val description =
                "Post a system notification to the user with a title and message body. Reversible write."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = SHOW_NOTIFICATION_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val title = args.string("title") ?: "Jarvis"
                val message = args.string("message") ?: args.string("body") ?: args.string("content") ?: args.string("text")
                if (message.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: message is required.",
                        error = "message is required",
                    )
                }
                return show(title, message).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Notification posted: \"$title\" - $message",
                            structuredData = mapOf("title" to title),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not post notification.",
                            error = error.message ?: "Notification failed",
                        )
                    },
                )
            }
        }

    private const val LAUNCH_APP_SCHEMA =
        """{"type":"object","properties":{"package_name":{"type":"string","description":"Android package name, e.g. com.google.android.youtube"},"app_name":{"type":"string","description":"App label if package name is unknown"}}}"""
    private const val COPY_CLIPBOARD_SCHEMA =
        """{"type":"object","properties":{"text":{"type":"string","description":"Text to copy"},"label":{"type":"string","description":"Optional description"}},"required":["text"]}"""
    private const val SHOW_NOTIFICATION_SCHEMA =
        """{"type":"object","properties":{"title":{"type":"string","description":"Notification title"},"message":{"type":"string","description":"Notification content text"}},"required":["message"]}"""
}
