package com.shifthud.notification

import com.shifthud.domain.planner.ClockOutPlan
import java.time.*

data class PlannerWarning(val consumed: Set<Int>, val boundary: Int?, val next: Instant?)
fun evaluatePlannerWarning(plan: ClockOutPlan, offsets: Set<Int>, consumed: Set<Int>?, now: Instant, eligible: Boolean): PlannerWarning {
    val deadline = plan.clockOut
    val passed = offsets.filter { if(it == 0) plan.reached else deadline != null && now >= deadline.minusSeconds(it*60L) }.toSet()
    val old = consumed ?: emptySet()
    if(consumed == null || !eligible) return PlannerWarning(old+passed,null,null)
    val candidate = (passed-old).minOrNull()
    val next = deadline?.let { end -> offsets.filter { it>0 && it !in old && it !in passed }.map { end.minusSeconds(it*60L) }.minOrNull() }
    return PlannerWarning(old+(passed-setOfNotNull(candidate)),candidate,next)
}
fun plannerWarningText(boundary: Int): Pair<String,String> = if(boundary==0)
    "Weekly target reached" to "Check your timecard. Manager approval required to leave."
else "Planned clock-out in $boundary min" to "Weekly hours estimate. Confirm departure with your manager."
fun coincidentClockOut(planned: Instant?, scheduled: Instant?) = planned != null && scheduled != null && Duration.between(planned,scheduled).abs() < Duration.ofSeconds(1)
