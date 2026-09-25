package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier

object DeviceTools {
    const val LAUNCH_APP = "launch_app"
    const val COPY_TO_CLIPBOARD = "copy_to_clipboard"
    const val READ_CLIPBOARD = "read_clipboard"
    const val SHOW_NOTIFICATION = "show_notification"
    const val SET_FLASHLIGHT = "set_flashlight"
    const val TOGGLE_BLUETOOTH = "toggle_bluetooth"
    const val OPEN_SETTINGS = "open_settings"
    const val LIST_INSTALLED_APPS = "list_installed_apps"
    const val INSTALL_APP = "install_app"

    fun all(
        launchApp: (suspend (packageNameOrName: String) -> Result<Unit>)? = null,
        copyClipboard: (suspend (text: String, label: String?) -> Result<Unit>)? = null,
        readClipboard: (suspend () -> Result<String?>)? = null,
        showNotification: (suspend (title: String, message: String) -> Result<Unit>)? = null,
        setFlashlight: (suspend (enabled: Boolean) -> Result<Unit>)? = null,
        toggleBluetooth: (suspend (enabled: Boolean) -> Result<String>)? = null,
        openSettings: (suspend (settingType: String) -> Result<Unit>)? = null,
        listInstalledApps: (suspend (query: String?) -> Result<List<String>>)? = null,
        installApp: (suspend (appName: String) -> Result<Unit>)? = null,
    ): List<Tool> = buildList {
        if (launchApp != null) add(launchApp(launchApp))
        if (copyClipboard != null) add(copyToClipboard(copyClipboard))
        if (readClipboard != null) add(readClipboard(readClipboard))
        if (showNotification != null) add(showNotification(showNotification))
        if (setFlashlight != null) add(setFlashlight(setFlashlight))
        if (toggleBluetooth != null) add(toggleBluetooth(toggleBluetooth))
        if (openSettings != null) add(openSettings(openSettings))
        if (listInstalledApps != null) add(listInstalledApps(listInstalledApps))
        if (installApp != null) add(installApp(installApp))
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
                    ?: if (args.string("command") != null || args.string("action") != null) "camera" else null
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
                        val notInstalled = error.message?.contains("Could not find installed application", ignoreCase = true) == true
                        ToolResult(
                            success = false,
                            observationText = if (notInstalled) "$target isn't installed." else (error.message ?: "Could not launch \"$target\"."),
                            error = if (notInstalled) "app_not_installed" else (error.message ?: "App launch failed"),
                            errorCode = if (notInstalled) com.jarvis.core.agent.execution.ErrorCode.APP_NOT_INSTALLED else null,
                            structuredData = mapOf("app" to target, "target" to target),
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

    fun setFlashlight(setTorch: suspend (Boolean) -> Result<Unit>): Tool =
        object : Tool {
            override val name = SET_FLASHLIGHT
            override val description =
                "Turn the device flashlight / torch on or off using native Android CameraManager. Reversible write."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = SET_FLASHLIGHT_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val enabled = args.boolean("enabled")
                    ?: args.boolean("state")
                    ?: args.boolean("value")
                    ?: args.boolean("action")
                    ?: args.string("state")?.let {
                        when (it.trim().lowercase()) {
                            "on", "true", "1", "enable", "enabled", "start" -> true
                            "off", "false", "0", "disable", "disabled", "stop" -> false
                            else -> null
                        }
                    }
                    ?: args.string("action")?.let {
                        when (it.trim().lowercase()) {
                            "on", "true", "1", "enable", "enabled", "turn_on" -> true
                            "off", "false", "0", "disable", "disabled", "turn_off" -> false
                            else -> null
                        }
                    }
                    ?: args.string("mode")?.let {
                        when (it.trim().lowercase()) {
                            "on", "true", "enable" -> true
                            "off", "false", "disable" -> false
                            else -> null
                        }
                    }
                    ?: args.boolean("turn_off")?.let { !it }
                    ?: args.boolean("off")?.let { !it }
                    ?: true
                return setTorch(enabled).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = if (enabled) "Flashlight turned on." else "Flashlight turned off.",
                            structuredData = mapOf("flashlight_on" to enabled),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not change flashlight: ${error.message ?: "Camera/Torch unavailable"}",
                            error = error.message ?: "Torch error",
                        )
                    },
                )
            }
        }

    fun toggleBluetooth(toggle: suspend (Boolean) -> Result<String>): Tool =
        object : Tool {
            override val name = TOGGLE_BLUETOOTH
            override val description =
                "Toggle or open Bluetooth controls on device. Uses Android native APIs or system settings. Reversible write."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = TOGGLE_BLUETOOTH_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val enabled = args.boolean("enabled")
                    ?: args.string("state")?.let { it.equals("on", ignoreCase = true) || it.equals("true", ignoreCase = true) }
                    ?: true
                return toggle(enabled).fold(
                    onSuccess = { msg ->
                        ToolResult(
                            success = true,
                            observationText = msg,
                            structuredData = mapOf("bluetooth_on" to enabled),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not modify Bluetooth: ${error.message ?: "Bluetooth unavailable"}",
                            error = error.message ?: "Bluetooth error",
                        )
                    },
                )
            }
        }

    fun openSettings(open: suspend (String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = OPEN_SETTINGS
            override val description =
                "Open native Android device Settings page (e.g. bluetooth, wifi, display, sound, battery, general). Action tier."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = OPEN_SETTINGS_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                val settingType = args.string("setting_type")
                    ?: args.string("type")
                    ?: args.string("page")
                    ?: "general"
                return open(settingType.trim()).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Opened $settingType settings.",
                            structuredData = mapOf("setting" to settingType),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not open settings: ${error.message ?: "Settings unavailable"}",
                            error = error.message ?: "Settings error",
                        )
                    },
                )
            }
        }

    fun listInstalledApps(list: suspend (query: String?) -> Result<List<String>>): Tool =
        object : Tool {
            override val name = LIST_INSTALLED_APPS
            override val description =
                "List or search installed applications on the device. Useful to see what apps are available or check if an app is installed."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = LIST_INSTALLED_APPS_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                val query = args?.string("query")?.trim()?.ifBlank { null }
                    ?: args?.string("search")?.trim()?.ifBlank { null }
                    ?: args?.string("app_name")?.trim()?.ifBlank { null }
                return list(query).fold(
                    onSuccess = { apps ->
                        val text = if (query != null) {
                            if (apps.isEmpty()) {
                                "No installed applications matching \"$query\" were found."
                            } else {
                                "Found ${apps.size} installed app(s) matching \"$query\":\n" + apps.joinToString("\n") { "• $it" }
                            }
                        } else {
                            if (apps.isEmpty()) {
                                "No installed launcher applications found."
                            } else {
                                "Installed applications (${apps.size}):\n" + apps.joinToString("\n") { "• $it" }
                            }
                        }
                        ToolResult(
                            success = true,
                            observationText = text,
                            structuredData = mapOf("count" to apps.size, "apps" to apps),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not list installed apps: ${error.message ?: "Unknown error"}",
                            error = error.message ?: "List apps failed",
                        )
                    },
                )
            }
        }

    fun installApp(install: suspend (String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = INSTALL_APP
            override val description =
                "Open Google Play Store to search for, download, or install an application. Reversible write tier."
            override val tier = PermissionTier.REVERSIBLE_WRITE
            override val parametersSchemaJson = INSTALL_APP_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                    ?: return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid_json",
                    )
                val appName = args.string("app_name")
                    ?: args.string("appName")
                    ?: args.string("package_name")
                    ?: args.string("packageName")
                    ?: args.string("query")
                    ?: ""
                if (appName.isBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "App name is required.",
                        error = "missing_app_name",
                    )
                }
                return install(appName.trim()).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Opened Google Play Store to install \"$appName\".",
                            structuredData = mapOf("app_name" to appName),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not open Play Store for \"$appName\": ${error.message ?: "Failed"}",
                            error = error.message ?: "install_failed",
                        )
                    },
                )
            }
        }

    private const val LAUNCH_APP_SCHEMA =
        """{"type":"object","properties":{"package_name":{"type":"string","description":"Android package name, e.g. com.google.android.youtube"},"app_name":{"type":"string","description":"App label if package name is unknown"}}}"""
    private const val INSTALL_APP_SCHEMA =
        """{"type":"object","properties":{"app_name":{"type":"string","description":"Name of the app to download or install from Play Store, e.g. youtube or whatsapp"}},"required":["app_name"]}"""
    private const val LIST_INSTALLED_APPS_SCHEMA =
        """{"type":"object","properties":{"query":{"type":"string","description":"Optional app name or keyword to filter the installed apps"}}}"""
    private const val COPY_CLIPBOARD_SCHEMA =
        """{"type":"object","properties":{"text":{"type":"string","description":"Text to copy"},"label":{"type":"string","description":"Optional description"}},"required":["text"]}"""
    private const val SHOW_NOTIFICATION_SCHEMA =
        """{"type":"object","properties":{"title":{"type":"string","description":"Notification title"},"message":{"type":"string","description":"Notification content text"}},"required":["message"]}"""
    private const val SET_FLASHLIGHT_SCHEMA =
        """{"type":"object","properties":{"enabled":{"type":"boolean","description":"True to turn on flashlight, false to turn off"},"state":{"type":"string","enum":["on","off"],"description":"Optional state 'on' or 'off'"}}}"""
    private const val TOGGLE_BLUETOOTH_SCHEMA =
        """{"type":"object","properties":{"enabled":{"type":"boolean","description":"True to enable Bluetooth, false to disable"}}}"""
    private const val OPEN_SETTINGS_SCHEMA =
        """{"type":"object","properties":{"setting_type":{"type":"string","enum":["bluetooth","wifi","display","sound","battery","general"],"description":"Settings screen to open"}}}"""
}
