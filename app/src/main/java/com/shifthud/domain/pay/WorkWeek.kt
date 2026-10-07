package com.shifthud.domain.pay

import java.time.*
import java.time.temporal.TemporalAdjusters

const val WORK_WEEK_LABEL = "THIS WEEK · Saturday–Friday"

data class WorkWeek(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date >= start && date <= endInclusive
}

/** A session belongs entirely to the work week of its local clock-in date. */
fun workWeekFor(date: LocalDate): WorkWeek {
    val saturday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
    return WorkWeek(saturday, saturday.plusDays(6))
}

data class WeekWindow(val start: Instant, val endExclusive: Instant)
fun currentWeek(now: Instant, zone: ZoneId): WeekWindow {
    val week = workWeekFor(now.atZone(zone).toLocalDate())
    return WeekWindow(week.start.atStartOfDay(zone).toInstant(), week.endInclusive.plusDays(1).atStartOfDay(zone).toInstant())
}
