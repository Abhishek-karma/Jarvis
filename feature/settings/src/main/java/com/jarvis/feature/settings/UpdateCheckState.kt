package com.jarvis.feature.settings

/** UI state of the Settings "App updates" row. */
sealed interface UpdateCheckState {
    /** Haven't checked this session (or last check cleared). */
    data object Idle : UpdateCheckState

    /** Check in flight. */
    data object Checking : UpdateCheckState

    /** A newer release exists — [apkUrl] opens the download. */
    data class Available(
        val version: String,
        val apkUrl: String,
    ) : UpdateCheckState

    /** This build is the newest release. */
    data object UpToDate : UpdateCheckState

    /** Network/API failure — user can retry. */
    data object Failed : UpdateCheckState
}
