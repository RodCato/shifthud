package com.shifthud.domain.usecase

import com.shifthud.domain.model.*
import java.time.*

/** Date is the clock-in date. Earlier wall times belong to the following day. */
data class HistoricalShiftInput(
    val date: LocalDate, val clockIn: LocalTime, val clockOut: LocalTime,
    val lunchStart: LocalTime? = null, val lunchEnd: LocalTime? = null,
) {
    fun session(zone: ZoneId, now: Instant): WorkSession {
        require((lunchStart == null) == (lunchEnd == null)) { "Enter both lunch start and lunch end, or leave both blank." }
        fun resolve(time: LocalTime): Instant {
            val local = (if (time < clockIn) date.plusDays(1) else date).atTime(time)
            val offsets = zone.rules.getValidOffsets(local)
            require(offsets.isNotEmpty()) { "This time does not exist because of daylight saving time. Check the date and punches." }
            return local.toInstant(offsets.first())
        }
        val start = resolve(clockIn)
        val end = resolve(clockOut)
        require(end > start) { "Clock out must be after clock in. Earlier clock-out times use the following day." }
        require(end <= now) { "A previous shift cannot end in the future." }
        val lunchIn = lunchStart?.let(::resolve)
        val lunchOut = lunchEnd?.let(::resolve)
        require(lunchIn == null || (lunchIn >= start && lunchOut!! >= lunchIn && lunchOut <= end)) {
            "Keep times in order: clock in, lunch start, lunch end, clock out. Check overnight punches."
        }
        return WorkSession(clockIn = start, lunchStart = lunchIn, lunchEnd = lunchOut,
            clockOut = end, state = ShiftState.COMPLETE, manuallyEntered = true)
    }
}

/** Link only one same-start-date schedule overlapping at least half of both intervals. */
fun historicalSchedule(session: WorkSession, schedule: List<ScheduledShift>, zone: ZoneId): Long? =
    schedule.filter { shift ->
        if (shift.date != session.clockIn.atZone(zone).toLocalDate()) false
        else {
            val start = shift.start.atZone(zone).toInstant()
            val end = shift.end.atZone(zone).toInstant()
            val overlap = Duration.between(maxOf(start, session.clockIn), minOf(end, requireNotNull(session.clockOut)))
            !overlap.isNegative && overlap.multipliedBy(2) >= Duration.between(start, end) &&
                overlap.multipliedBy(2) >= Duration.between(session.clockIn, session.clockOut)
        }
    }.singleOrNull()?.id

class SessionOverlapException(val existing: WorkSession) : IllegalArgumentException("A work session already exists during this time.")
