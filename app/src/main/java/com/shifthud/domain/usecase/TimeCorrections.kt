package com.shifthud.domain.usecase

import com.shifthud.domain.model.*
import java.time.*

enum class TimeEvent(val label: String) {
    CLOCK_IN("Clock in"), LUNCH_START("Lunch start"), LUNCH_END("Lunch end"), CLOCK_OUT("Clock out");
    fun timestamp(s: WorkSession): Instant? = when (this) {
        CLOCK_IN -> s.clockIn; LUNCH_START -> s.lunchStart; LUNCH_END -> s.lunchEnd; CLOCK_OUT -> s.clockOut
    }
}
data class AutoLunchSettings(val enabled: Boolean = true, val minutes: Int = 60)
val AUTO_LUNCH_CHOICES = listOf(30, 45, 60, 90)

fun correctTime(s: WorkSession, event: TimeEvent, value: Instant, now: Instant): WorkSession {
    require(event.timestamp(s) != null) { "This event has not been recorded yet." }
    val ordered = TimeEvent.entries.mapNotNull { if (it == event) value else it.timestamp(s) }
    require(ordered.all { it <= now }) { "A recorded time cannot be in the future. Check your device clock." }
    require(ordered.zipWithNext().all { (a, b) -> a <= b }) {
        "Keep times in order: clock in, lunch start, lunch end, clock out. Check the date for overnight shifts."
    }
    if (value == event.timestamp(s) && !(event == TimeEvent.LUNCH_END && s.lunchEndAutomatic)) return s
    return s.copy(clockIn = if (event == TimeEvent.CLOCK_IN) value else s.clockIn,
        lunchStart = if (event == TimeEvent.LUNCH_START) value else s.lunchStart,
        lunchEnd = if (event == TimeEvent.LUNCH_END) value else s.lunchEnd,
        clockOut = if (event == TimeEvent.CLOCK_OUT) value else s.clockOut,
        lunchEndAutomatic = s.lunchEndAutomatic && event != TimeEvent.LUNCH_END,
        correctionRevision = s.correctionRevision + 1)
}

/** Preserve the original offset during repeated DST hours; reject nonexistent local times. */
fun correctedInstant(date: LocalDate, time: LocalTime, zone: ZoneId, original: Instant): Instant {
    val local = date.atTime(time)
    val offsets = zone.rules.getValidOffsets(local)
    require(offsets.isNotEmpty()) { "This time does not exist on that date because of daylight saving time." }
    val preferred = original.atZone(zone).offset.takeIf { it in offsets } ?: offsets.first()
    return local.toInstant(preferred)
}
