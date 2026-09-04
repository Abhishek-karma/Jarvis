package com.jarvis.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Groups epoch-millis timestamps into the sections the History drawer shows. */
enum class TimeGroup(val label: String) {
    PINNED("Pinned"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 days"),
    OLDER("Older"),
}

object TimeGrouping {
    fun groupFor(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): TimeGroup {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when {
            date == now -> TimeGroup.TODAY
            date == now.minusDays(1) -> TimeGroup.YESTERDAY
            date.isAfter(now.minusDays(7)) -> TimeGroup.LAST_7_DAYS
            else -> TimeGroup.OLDER
        }
    }

    /** Stable display order for sections. */
    val ORDER = listOf(
        TimeGroup.PINNED,
        TimeGroup.TODAY,
        TimeGroup.YESTERDAY,
        TimeGroup.LAST_7_DAYS,
        TimeGroup.OLDER,
    )
}
