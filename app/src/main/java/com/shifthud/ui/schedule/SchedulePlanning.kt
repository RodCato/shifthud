package com.shifthud.ui.schedule

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.planner.*
import java.time.*

/** Presentation adapter only: all paid-time projections use the existing planner. */
data class SchedulePlanning(val plan: ClockOutPlan, val employerPaid: Duration?, val estimatedRows: Int,
    val shiftCount: Int, val projectedGross: Long?, val daily: Map<Long, DailySchedulePlan> = emptyMap())

data class DailySchedulePlan(val paid: Duration?, val lunch: Duration, val warning: String? = null)

fun schedulePlanning(week: WorkWeek, schedule: List<ScheduledShift>, sessions: List<WorkSession>,
    settings: PlannerSettings, target: Duration, now: Instant, zone: ZoneId, rates: List<PayRate> = emptyList()): SchedulePlanning {
    val shifts=week.scheduleRows(schedule).distinctBy { it.id }
    val start=week.start.atStartOfDay(zone).toInstant()
    val plan=clockOutPlan(sessions,shifts,target,settings,maxOf(now,start),zone,week.start)
    val reported=shifts.filter { it.employerNetMinutes != null && Duration.ofMinutes(it.employerNetMinutes.toLong()) <= it.duration(zone) }
    val missing=shifts.filterNot { it in reported }
    // Legacy schedule assumptions are only a labelled fallback, never employer payroll facts.
    // The established default/minimum handles an absent lunch; null never becomes zero.
    val fallback=clockOutPlan(emptyList(),missing,target,settings.copy(dynamicWeekly=false),start,zone,week.start)
    val employer=if(shifts.isEmpty()) null else reported.fold(fallback.projected) { sum,s -> sum.plusMinutes(s.employerNetMinutes!!.toLong()) }
    // A single effective rate for every start date permits an aggregate base-pay estimate.
    // Do not guess allocations when a rate changes within the week.
    val weekRates=(0L..6L).map { offset -> rates.filter { it.effectiveFrom <= week.start.plusDays(offset) }.maxByOrNull { it.effectiveFrom }?.centsPerHour }
    val rate=weekRates.distinct().singleOrNull()
    // Resolve final-day lunch policy in the full week before asking the same engine for a row.
    val daily = shifts.associate { shift ->
        val resolvedShift = shift.copy(plannedLunchMinutes = settings.lunch(shift,plan.finalDate).toMinutes().toInt(), lunchPlanApplied = true)
        val rowPlan = clockOutPlan(sessions, listOf(resolvedShift), target, settings, maxOf(now,start), zone, week.start)
        val overlap = shifts.any { it.id != shift.id && it.start < shift.end && it.end > shift.start }
        val unavailable = rowPlan.issues.firstOrNull { it.contains("no linked punch") || it.contains("unlinked punch overlaps") }
        val warning = if(overlap) "Overlapping schedules · Review weekly projection" else unavailable
        shift.id to DailySchedulePlan(if(warning == null) rowPlan.futurePaid else null,
            settings.lunch(shift,plan.finalDate), warning)
    }
    return SchedulePlanning(plan,employer,missing.size,shifts.size,rate?.let { grossCents(plan.projected,it) },daily)
}
