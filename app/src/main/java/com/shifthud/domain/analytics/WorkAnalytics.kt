package com.shifthud.domain.analytics

import com.shifthud.domain.calendar.WorkCalendarDay
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.weekly.WeeklyTargetSettings
import java.time.*

/** Half-open local date ranges; all elapsed work belongs to its local clock-in date. */
data class AnalyticsPeriod(val start: LocalDate, val endExclusive: LocalDate, val weekly: Boolean) {
    init { require(endExclusive > start) }
    fun move(amount: Long): AnalyticsPeriod = if (weekly) week(start.plusWeeks(amount)) else month(YearMonth.from(start).plusMonths(amount))
    companion object {
        fun week(date: LocalDate): AnalyticsPeriod = workWeekFor(date).let { AnalyticsPeriod(it.start, it.endInclusive.plusDays(1), true) }
        fun month(month: YearMonth) = AnalyticsPeriod(month.atDay(1), month.plusMonths(1).atDay(1), false)
    }
}
data class WorkAnalytics(val period: AnalyticsPeriod, val breakdown: SessionBreakdown, val days: List<WorkCalendarDay>, val target: WeeklyTargetSettings, val asOf: Instant) {
    val paid get() = breakdown.paid
    val grossCents get() = breakdown.grossCents
    val completedSessions get() = breakdown.contributions.count { it.session.state == ShiftState.COMPLETE }
    val activeSessions get() = breakdown.contributions.size - completedSessions
    val completedPaid get() = breakdown.contributions.filter { it.session.state == ShiftState.COMPLETE }.fold(Duration.ZERO) { sum, row -> sum.plus(row.paid) }
    val accruedPaid get() = paid.minus(completedPaid)
    val workedDays get() = days.count { it.paidDuration > Duration.ZERO }
    val averagePaid get() = if (workedDays == 0) Duration.ZERO else paid.dividedBy(workedDays.toLong())
    val targetProgress get() = paid.toMillis().toDouble() / target.target.toMillis()
}
fun workAnalytics(period: AnalyticsPeriod, sessions: List<WorkSession>, schedule: List<ScheduledShift>, rates: List<PayRate>, now: Instant, zone: ZoneId, estimator: PayEstimator, target: WeeklyTargetSettings): WorkAnalytics {
    val selected = sessions.distinctBy { it.id }.filter { it.clockIn <= now && it.clockIn.atZone(zone).toLocalDate().let { date -> date >= period.start && date < period.endExclusive } }
    val breakdown = estimator.breakdown(selected, rates, now, zone)
    val byDate = breakdown.contributions.groupBy { it.session.clockIn.atZone(zone).toLocalDate() }
    val plans = schedule.groupBy { it.date }
    val today = now.atZone(zone).toLocalDate()
    val days = generateSequence(period.start) { it.plusDays(1) }.takeWhile { it < period.endExclusive }.map { date ->
        val daily = SessionBreakdown(byDate[date].orEmpty())
        WorkCalendarDay(date, date == today, date in workWeekFor(today), plans[date].orEmpty().chronological(), daily.contributions.map { it.session }, daily)
    }.toList()
    return WorkAnalytics(period, breakdown, days, target, now)
}
