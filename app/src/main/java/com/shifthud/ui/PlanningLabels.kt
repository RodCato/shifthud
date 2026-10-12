package com.shifthud.ui

import com.shifthud.ui.schedule.SchedulePlanning
import java.time.Duration

fun SchedulePlanning.scheduledLabel(): String = when {
    employerPaid == null -> "Scheduled: unknown — no saved shifts"
    estimatedRows == 0 -> "Scheduled: ${employerPaid.display()} (employer-reported)"
    estimatedRows == shiftCount -> "Scheduled: ${employerPaid.display()} (estimated)"
    else -> "Scheduled: ~${employerPaid.display()} (partially estimated)"
}
fun SchedulePlanning.metadataLabel() = "Employer hours available for ${shiftCount-estimatedRows} of $shiftCount saved shifts."

/** Presentation only: a whole-minute countdown complements the floored worked total. */
fun Duration.countdownLabel(): String {
    val minutes=toMinutes().coerceAtLeast(0)
    return Duration.ofMinutes(minutes + if(this > Duration.ofMinutes(minutes)) 1 else 0).display()
}
fun Duration.excessLabel(): String = if(this > Duration.ZERO && this < Duration.ofMinutes(1)) "<1m" else display()
const val PLANNING_ROUNDING_NOTE = "Worked and projected totals round down to whole minutes; remaining time and shortfall round up. Separately rounded components may differ by one minute. Calculations and clock-out recommendations retain full precision."
