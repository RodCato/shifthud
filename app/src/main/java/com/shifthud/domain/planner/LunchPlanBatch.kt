package com.shifthud.domain.planner

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.workWeekFor
import java.time.*

/** Only unworked future shifts; even unlinked overlapping actual work protects a schedule. */
fun lunchPlanCandidates(schedule: List<ScheduledShift>, sessions: List<WorkSession>, now: Instant,
                        zone: ZoneId, replaceOverrides: Boolean): List<ScheduledShift> {
    val week=workWeekFor(now.atZone(zone).toLocalDate())
    return schedule.filter { shift ->
        val start=shift.start.atZone(zone).toInstant();val end=shift.end.atZone(zone).toInstant()
        shift.date in week && start>now && (replaceOverrides || shift.usesGlobalLunch || shift.plannedLunchMinutes==null) &&
            sessions.none { it.scheduledShiftId==shift.id || ((it.scheduledShiftId==null || it.clockOut!=null) && it.clockIn<end && (it.clockOut?:Instant.MAX)>start) }
    }
}
fun hypotheticalLunchPlan(schedule: List<ScheduledShift>, candidates: List<ScheduledShift>, minutes: Int) =
    schedule.map { if(it.id in candidates.map { s -> s.id }.toSet()) it.copy(plannedLunchMinutes=minutes,usesGlobalLunch=true,lunchPlanApplied=true) else it }
