package com.jarvis.core.agent.needle

/**
 * Structured tool definition for Needle 3 function-calling engine.
 */
data class NeedleToolDef(
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
)

/**
 * Standard capability catalogue exposed to Needle 3.
 * Simple native Android capabilities are exposed here for high-speed routing.
 */
object NeedleToolCatalogue {
    val LAUNCH_APP = NeedleToolDef(
        name = "launch_app",
        description = "Launch or open an installed Android application.",
        parametersSchemaJson = """{"type":"object","properties":{"app_name":{"type":"string","description":"Name of application to launch"}},"required":["app_name"]}""",
    )

    val OPEN_SETTINGS = NeedleToolDef(
        name = "open_settings",
        description = "Open an Android system settings destination.",
        parametersSchemaJson = """{"type":"object","properties":{"setting_type":{"type":"string","description":"Settings category: wifi, bluetooth, display, sound, battery, accessibility, general"}},"required":["setting_type"]}""",
    )

    val SET_FLASHLIGHT = NeedleToolDef(
        name = "set_flashlight",
        description = "Turn device flashlight/torch on or off.",
        parametersSchemaJson = """{"type":"object","properties":{"enabled":{"type":"boolean","description":"True to turn on, false to turn off"}},"required":["enabled"]}""",
    )

    val GET_BATTERY = NeedleToolDef(
        name = "get_battery",
        description = "Get current battery level and charging status.",
        parametersSchemaJson = """{"type":"object","properties":{}}""",
    )

    val GET_TIME = NeedleToolDef(
        name = "get_time",
        description = "Get current local date and time.",
        parametersSchemaJson = """{"type":"object","properties":{}}""",
    )

    val ADJUST_VOLUME = NeedleToolDef(
        name = "adjust_volume",
        description = "Adjust, mute, or unmute device media volume.",
        parametersSchemaJson = """{"type":"object","properties":{"action":{"type":"string","enum":["up","down","mute","unmute"]}},"required":["action"]}""",
    )

    val PLAY_MEDIA = NeedleToolDef(
        name = "play_media",
        description = "Play music, song, artist, playlist, or video.",
        parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"Song or artist to play"},"app_name":{"type":"string","description":"Music app"}}}""",
    )

    val MEDIA_CONTROL = NeedleToolDef(
        name = "media_control",
        description = "Control media playback (play, pause, next, previous, stop).",
        parametersSchemaJson = """{"type":"object","properties":{"action":{"type":"string","enum":["play","pause","toggle","next","previous","stop"]}},"required":["action"]}""",
    )

    val STORAGE_FREE = NeedleToolDef(
        name = "storage_free",
        description = "Check available free internal storage.",
        parametersSchemaJson = """{"type":"object","properties":{}}""",
    )

    val NETWORK_STATUS = NeedleToolDef(
        name = "network_status",
        description = "Check current network and Wi-Fi connection status.",
        parametersSchemaJson = """{"type":"object","properties":{}}""",
    )

    val INSTALL_APP = NeedleToolDef(
        name = "install_app",
        description = "Open Google Play Store to install an application.",
        parametersSchemaJson = """{"type":"object","properties":{"app_name":{"type":"string","description":"Name of application to install"}},"required":["app_name"]}""",
    )

    val LIST_INSTALLED_APPS = NeedleToolDef(
        name = "list_installed_apps",
        description = "List all installed applications on the device.",
        parametersSchemaJson = """{"type":"object","properties":{}}""",
    )

    val ALL_TOOLS: List<NeedleToolDef> = listOf(
        LAUNCH_APP,
        OPEN_SETTINGS,
        SET_FLASHLIGHT,
        GET_BATTERY,
        GET_TIME,
        ADJUST_VOLUME,
        PLAY_MEDIA,
        MEDIA_CONTROL,
        STORAGE_FREE,
        NETWORK_STATUS,
        INSTALL_APP,
        LIST_INSTALLED_APPS,
    )
}
