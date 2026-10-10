package com.shifthud.domain.calendar

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import java.time.*

val WORK_WEEK_DAYS = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
data class CalendarRange(val start: LocalDate, val endExclusive: LocalDate)
fun calendarRange(month: YearMonth): CalendarRange {
    val start = workWeekFor(month.atDay(1)).start
    val end = maxOf(workWeekFor(month.atEndOfMonth()).endInclusive.plusDays(1), start.plusDays(35))
    return CalendarRange(start, end)
}

data class WorkCalendarDay(
    val date: LocalDate, val isToday: Boolean, val isCurrentWeek: Boolean,
    val scheduledShifts: List<ScheduledShift>, val workSessions: List<WorkSession>,
    val breakdown: SessionBreakdown,
) {
    val hasCompletedSession get() = workSessions.any { it.state == ShiftState.COMPLETE }
    val hasActiveSession get() = workSessions.any { it.state == ShiftState.WORKING || it.state == ShiftState.ON_LUNCH }
    val paidDuration get() = breakdown.paid
    val estimatedGross get() = breakdown.grossCents
    // Distinct shapes/text accompany color; never infer attendance from a schedule.
    val indicator get() = when { hasActiveSession -> "▶"; hasCompletedSession -> "●"; scheduledShifts.isNotEmpty() -> "○"; else -> "–" }
    val stateDescription get() = listOfNotNull(
        "Today".takeIf { isToday }, "Current work week".takeIf { isCurrentWeek },
        "Active session".takeIf { hasActiveSession },
        "Worked, ${workSessions.count { it.state == ShiftState.COMPLETE }} completed sessions".takeIf { hasCompletedSession },
        "Scheduled, ${scheduledShifts.size} shifts".takeIf { scheduledShifts.isNotEmpty() },
        "No shift".takeIf { scheduledShifts.isEmpty() && workSessions.isEmpty() },
    ).joinToString(", ")
}

fun workCalendar(month: YearMonth, schedule: List<ScheduledShift>, sessions: List<WorkSession>, rates: List<PayRate>, now: Instant, zone: ZoneId, estimator: PayEstimator): List<WorkCalendarDay> {
    val range = calendarRange(month)
    val today = now.atZone(zone).toLocalDate()
    val week = workWeekFor(today)
    val byDate = sessions.groupBy { it.clockIn.atZone(zone).toLocalDate() }
    val planned = schedule.groupBy { it.date }
    return generateSequence(range.start) { it.plusDays(1) }.takeWhile { it < range.endExclusive }.map { date ->
        val actual = byDate[date].orEmpty().sortedWith(compareBy<WorkSession> { it.clockIn }.thenBy { it.id })
        WorkCalendarDay(date, date == today, date in week, planned[date].orEmpty().chronological(), actual,
            estimator.breakdown(actual, rates, now, zone))
    }.toList()
}

/** Presentation only: floor to whole minutes; timestamps and pay retain full precision. */
fun recordDuration(duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
