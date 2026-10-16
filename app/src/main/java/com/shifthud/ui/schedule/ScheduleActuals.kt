package com.shifthud.ui.schedule

import com.shifthud.domain.calendar.recordDuration
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ScheduleActuals(val summary: String, val lunch: String?, val intervals: List<String> = emptyList())

/** Read-only presentation of explicitly linked punches; plans never fill missing actuals. */
fun scheduleActuals(shift: ScheduledShift, sessions: List<WorkSession>, plannedMinutes: Int?, now: Instant,
    zone: ZoneId, locale: Locale, use24Hour: Boolean, projection: DailySchedulePlan? = null): ScheduleActuals {
    val linked = sessions.filter { it.scheduledShiftId == shift.id }.sortedBy { it.clockIn }
    val status = scheduleCompletion(listOf(shift), linked).states.getValue(shift.id)
    if (linked.isEmpty()) return ScheduleActuals(
        projection?.paid?.let { "${recordDuration(it)} projected paid" } ?: "Projected paid unavailable",
        "Planned lunch: ${projection?.lunch?.toMinutes()?.let { "${it}m" } ?: plannedMinutes?.let { "${it}m" } ?: "Loading…"}" +
            (projection?.warning?.let { "\n$it" } ?: ""))
    val engine = ShiftEngine()
    val paid = linked.fold(Duration.ZERO) { total, session -> total.plus(engine.durations(session, 1, now).paid) }
    val time = DateTimeFormatter.ofPattern(if(use24Hour) "HH:mm" else "h:mm a", locale)
    val dateTime = DateTimeFormatter.ofPattern(if(use24Hour) "MMM d HH:mm" else "MMM d h:mm a", locale)
    fun stamp(value: Instant): String {
        val local = value.atZone(zone)
        return local.format(if(local.toLocalDate() == shift.date) time else dateTime)
    }
    val completed = linked.filter { it.lunchStart != null && it.lunchEnd != null }
    val intervals = completed.map { "${stamp(it.lunchStart!!)} – ${stamp(it.lunchEnd!!)} (${recordDuration(Duration.between(it.lunchStart, it.lunchEnd))})" }
    val active = linked.filter { it.lunchStart != null && it.lunchEnd == null }
    val lunch = buildList {
        when (intervals.size) {
            0 -> if(active.isEmpty()) add("No lunch recorded")
            1 -> add("Lunch: ${intervals.single()}")
            else -> add("Lunch: ${recordDuration(completed.fold(Duration.ZERO) { total, s -> total.plus(Duration.between(s.lunchStart, s.lunchEnd)) })} total · ${intervals.size} intervals · Expand for times")
        }
        active.forEach { add("Lunch in progress · Started ${stamp(it.lunchStart!!)} · ${recordDuration(engine.durations(it, 1, now).lunch)} elapsed") }
    }.joinToString("\n")
    return ScheduleActuals("${status.label} · ${recordDuration(paid)} actual paid", lunch, if(intervals.size>1) intervals else emptyList())
}
