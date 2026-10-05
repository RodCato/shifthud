package com.shifthud.domain.model

import java.time.*

enum class ShiftState { NOT_STARTED, WORKING, ON_LUNCH, COMPLETE }
data class ScheduledShift(
    val id: Long = 0, val date: LocalDate, val scheduledStart: LocalTime,
    val scheduledEnd: LocalTime, val plannedLunchMinutes: Int? = null, val notes: String? = null,
) {
    init { require(plannedLunchMinutes == null || plannedLunchMinutes in 0..1440) }
    val start: LocalDateTime get() = date.atTime(scheduledStart)
    val end: LocalDateTime get() = (if (scheduledEnd <= scheduledStart) date.plusDays(1) else date).atTime(scheduledEnd)
    fun duration(zone: ZoneId): Duration = Duration.between(start.atZone(zone), end.atZone(zone))
}
fun Iterable<ScheduledShift>.chronological() = sortedWith(compareBy<ScheduledShift> { it.start }.thenBy { it.id })
data class WorkSession(
    val id: Long = 0, val scheduledShiftId: Long? = null, val clockIn: Instant,
    val lunchStart: Instant? = null, val lunchEnd: Instant? = null,
    val clockOut: Instant? = null, val state: ShiftState = ShiftState.WORKING,
    val autoLunchMinutes: Int? = null, val lunchEndAutomatic: Boolean = false, val correctionRevision: Long = 0,
) {
    val autoLunchEndTarget: Instant? get() = lunchStart?.let { start -> autoLunchMinutes?.let { start.plusSeconds(it * 60L) } }
    init {
        require(autoLunchMinutes == null || autoLunchMinutes > 0)
        require(!lunchEndAutomatic || lunchEnd != null)
        require(state != ShiftState.NOT_STARTED)
        require(lunchStart == null || lunchStart >= clockIn)
        require(lunchEnd == null || (lunchStart != null && lunchEnd >= lunchStart))
        require((state == ShiftState.COMPLETE) == (clockOut != null))
        require((state == ShiftState.ON_LUNCH) == (lunchStart != null && lunchEnd == null))
        require(clockOut == null || clockOut >= (lunchEnd ?: clockIn))
    }
}
data class ShiftDurations(val store: Duration, val paid: Duration, val activeWork: Duration, val lunch: Duration, val lunchRemaining: Duration)
