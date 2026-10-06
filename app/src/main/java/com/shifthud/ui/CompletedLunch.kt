package com.shifthud.ui

import com.shifthud.domain.model.WorkSession
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display-only values; persisted event timestamps remain authoritative. */
data class CompletedLunch(val start: Instant, val end: Instant, val duration: Duration, val interval: String, val automatic: Boolean = false) {
    private val minutes get() = duration.toMinutes().coerceAtLeast(0)
    val durationLabel get() = (if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m") + if (automatic) " · Auto" else ""
    val detail get() = "Lunch taken\n$interval · $durationLabel"
    val compactDetail get() = "Lunch taken · $durationLabel\n$interval"
}

fun completedLunch(session: WorkSession, zone: ZoneId, locale: Locale, use24Hour: Boolean): CompletedLunch? {
    val start = session.lunchStart ?: return null
    val end = session.lunchEnd ?: return null
    val time = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
    return CompletedLunch(start, end, Duration.between(start, end),
        "${start.atZone(zone).format(time)} – ${end.atZone(zone).format(time)}", session.lunchEndAutomatic)
}
