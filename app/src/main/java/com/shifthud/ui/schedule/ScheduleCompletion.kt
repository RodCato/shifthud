package com.shifthud.ui.schedule

import com.shifthud.domain.model.ScheduledShift
import com.shifthud.domain.model.WorkSession

enum class ScheduleCompletionStatus(val label: String) {
    SCHEDULED("Scheduled"), WORKING("Working"), ON_LUNCH("On lunch"), COMPLETED("Completed")
}

data class ScheduleCompletion(val states: Map<Long, ScheduleCompletionStatus>) {
    val completed: Int get() = states.values.count { it == ScheduleCompletionStatus.COMPLETED }
    val summary: String get() = "$completed of ${states.size} shifts completed"
}

/**
 * Only an explicit persisted association identifies work for a scheduled shift. Null means
 * unscheduled, not a same-date match. Active work takes precedence over completed segments;
 * completion never depends on reaching the scheduled end. No inferred associations are stored.
 */
fun scheduleCompletion(shifts: List<ScheduledShift>, sessions: List<WorkSession>): ScheduleCompletion {
    val linked=sessions.filter { it.scheduledShiftId != null }.groupBy { it.scheduledShiftId }
    return ScheduleCompletion(shifts.associate { shift ->
        val records=linked[shift.id].orEmpty()
        val active=records.filter { it.clockOut == null }
        shift.id to when {
            active.any { it.lunchStart != null && it.lunchEnd == null } -> ScheduleCompletionStatus.ON_LUNCH
            active.isNotEmpty() -> ScheduleCompletionStatus.WORKING
            records.isNotEmpty() && records.all { it.clockOut?.let { end -> end >= (it.lunchEnd ?: it.clockIn) } == true } -> ScheduleCompletionStatus.COMPLETED
            else -> ScheduleCompletionStatus.SCHEDULED
        }
    })
}
