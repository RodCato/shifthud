package com.shifthud.domain.planner

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.workWeekFor
import java.time.*

enum class LunchStrategy { FULL, SHORTER, CUSTOM }
data class PlannerSettings(val plannedMinutes: Int = 60, val minimumMinutes: Int = 30,
    val strategy: LunchStrategy = LunchStrategy.FULL, val custom: Map<LocalDate, Int> = emptyMap(), val alerts: Set<Int> = emptySet(),
    val weeklyLunches: Map<LocalDate, Int> = emptyMap(), val dynamicWeekly: Boolean = false) {
    init { require(alerts.all { it in setOf(60,30,15,0) }); require(minimumMinutes in 1..240 && plannedMinutes in minimumMinutes..240); require(custom.values.all { it in 1..240 }); require(weeklyLunches.all { (date,minutes) -> date.dayOfWeek == DayOfWeek.SATURDAY && minutes in 31..240 }) }
    fun weeklyMinutes(date: LocalDate): Int = (weeklyLunches[workWeekFor(date).start] ?: plannedMinutes).coerceIn(maxOf(31, minimumMinutes), 240)
    fun withWeek(date: LocalDate, minutes: Int): PlannerSettings {
        require(minutes in maxOf(31, minimumMinutes)..240)
        return copy(weeklyLunches = weeklyLunches + (workWeekFor(date).start to minutes), dynamicWeekly = true)
    }
    fun lunch(shift: ScheduledShift, finalDate: LocalDate?): Duration = Duration.ofMinutes((if(dynamicWeekly) weeklyMinutes(shift.date) else if(shift.lunchPlanApplied) shift.plannedLunchMinutes ?: plannedMinutes else when(strategy) {
        LunchStrategy.FULL -> shift.plannedLunchMinutes ?: plannedMinutes
        LunchStrategy.SHORTER -> if(shift.date == finalDate) shift.plannedLunchMinutes ?: plannedMinutes else minimumMinutes
        LunchStrategy.CUSTOM -> custom[shift.date] ?: shift.plannedLunchMinutes ?: plannedMinutes
    }).coerceAtLeast(minimumMinutes).toLong())
}
data class ClockOutPlan(val completed: Duration, val activePaid: Duration, val remaining: Duration,
    val futurePaid: Duration, val projected: Duration, val target: Duration, val clockOut: Instant?,
    val finalDate: LocalDate?, val lunchRemaining: Duration, val issues: List<String>, val liveFinalDay: Boolean) {
    val worked get() = completed.plus(activePaid)
    val reached get() = worked >= target
    val excess get() = projected.minus(target).coerceAtLeast(Duration.ZERO)
    val shortfall get() = target.minus(projected).coerceAtLeast(Duration.ZERO)
}
private data class Span(val start: Instant, val end: Instant)
private fun union(spans: List<Span>): List<Span> {
    val result = mutableListOf<Span>()
    spans.filter { it.end > it.start }.sortedBy { it.start }.forEach { next ->
        val last = result.lastOrNull()
        if(last != null && next.start <= last.end) result[result.lastIndex] = Span(last.start, maxOf(last.end,next.end)) else result += next
    }
    return result
}
private fun total(spans: List<Span>) = union(spans).fold(Duration.ZERO) { sum, s -> sum.plus(Duration.between(s.start,s.end)) }
private fun paidSpans(session: WorkSession, now: Instant): List<Span> {
    val end = minOf(session.clockOut ?: now,now)
    val lunch = session.lunchStart ?: return listOf(Span(session.clockIn,end))
    return listOf(Span(session.clockIn,minOf(lunch,end))) + listOfNotNull(session.lunchEnd?.let { Span(it,end) })
}
/** Schedules are assumptions; actual timestamp intervals always replace them. No records are mutated. */
fun clockOutPlan(sessions: List<WorkSession>, schedule: List<ScheduledShift>, target: Duration,
                 settings: PlannerSettings, now: Instant, zone: ZoneId, weekStart: LocalDate? = null): ClockOutPlan {
    require(weekStart == null || weekStart.dayOfWeek == DayOfWeek.SATURDAY)
    val week = workWeekFor(weekStart ?: now.atZone(zone).toLocalDate())
    val records = sessions.distinctBy { it.id }.filter { it.clockIn <= now && it.clockIn.atZone(zone).toLocalDate() in week }
    val completed = total(records.filter { it.clockOut != null }.flatMap { paidSpans(it,now) })
    val worked = total(records.flatMap { paidSpans(it,now) })
    val remaining = target.minus(worked).coerceAtLeast(Duration.ZERO)
    val shifts = schedule.distinctBy { it.id }.filter { it.date in week }.sortedBy { it.start }
    val finalDate = shifts.maxOfOrNull { it.date }
    val issues = mutableListOf<String>()
    val active = records.firstOrNull { it.clockOut == null }
    data class Planned(val shift: ScheduledShift, val start: Instant, val end: Instant, val lunch: Duration, val paid: Duration)
    val planned = mutableListOf<Planned>()
    shifts.forEach { shift ->
        val linked = records.filter { it.scheduledShiftId == shift.id }
        if(linked.isNotEmpty() && linked.all { it.clockOut != null }) return@forEach
        val live = linked.firstOrNull { it.clockOut == null }
        val start = shift.start.atZone(zone).toInstant()
        val end = shift.end.atZone(zone).toInstant()
        if(live == null && start < now) { issues += "A scheduled shift has no linked punch; missed time is not counted."; return@forEach }
        if(end <= now) return@forEach
        val from = if(live != null) now else start
        if(records.any { it.scheduledShiftId != shift.id && it.clockIn < end && (it.clockOut ?: now) > start }) {
            issues += "An unlinked punch overlaps a schedule; review its assignment."; return@forEach
        }
        val lunch = when {
            live?.lunchEnd != null -> Duration.ZERO
            live?.lunchStart != null -> Duration.between(now,live.autoLunchEndTarget ?: live.lunchStart.plus(settings.lunch(shift,finalDate))).coerceAtLeast(Duration.ZERO)
            else -> settings.lunch(shift,finalDate)
        }
        if(live?.lunchStart != null && live.lunchEnd == null && lunch.isZero) issues += "Lunch is still open; the resume time is uncertain."
        planned += Planned(shift,from,end,lunch,Duration.between(from,end).minus(lunch).coerceAtLeast(Duration.ZERO))
    }
    val overlaps = planned.zipWithNext().any { (a,b) -> a.end > b.start }
    if(overlaps) issues += "Overlapping schedules need review; overlapping time is counted once."
    // Place assumed unpaid time first solely to bound union arithmetic. No lunch start time is asserted.
    val future = total(planned.map { Span(minOf(it.end,it.start.plus(it.lunch)),it.end) })
    val last = planned.lastOrNull()
    if(last == null) issues += "No remaining saved schedule. Add or confirm your workdays."
    if(active != null && shifts.none { it.id == active.scheduledShiftId }) issues += "Current shift is unscheduled; its future duration is unknown."
    val neededLast = remaining.minus(planned.dropLast(1).fold(Duration.ZERO) { sum,p -> sum.plus(p.paid) })
    var recommendation: Instant? = null
    if(!remaining.isZero && last != null && !overlaps && issues.isEmpty()) {
        when {
            neededLast <= Duration.ZERO -> issues += "The target is reached before the final shift. Review earlier shifts with your manager."
            neededLast > last.paid -> issues += "Saved shifts fall short of the target; no extra work is assumed."
            else -> recommendation = last.start.plus(last.lunch).plus(neededLast)
        }
    }
    return ClockOutPlan(completed,worked.minus(completed),remaining,future,worked.plus(future),target,recommendation,
        finalDate,last?.lunch ?: Duration.ZERO,issues.distinct(),active != null && active.clockIn.atZone(zone).toLocalDate() == finalDate)
}
