package com.shifthud.notification.upcoming

import com.shifthud.domain.model.ScheduledShift
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class UpcomingSettings(val enabled: Boolean = true, val time: LocalTime = LocalTime.of(20, 0), val daysOff: Boolean = false)
data class UpcomingReceipt(val date: LocalDate, val fingerprint: String, val postedAt: Instant)
data class UpcomingContent(val title: String, val text: String, val expanded: String = text)
data class UpcomingPlan(val date: LocalDate, val fingerprint: String, val content: UpcomingContent?, val next: Instant)

/** Calendar-day lookup, independent of payroll weeks and active work sessions. */
fun tomorrowShifts(schedule: List<ScheduledShift>, today: LocalDate) = schedule.filter { it.date == today.plusDays(1) }
    .sortedWith(compareBy<ScheduledShift> { it.scheduledStart }.thenBy { it.scheduledEnd }.thenBy { it.id })
fun scheduleFingerprint(shifts: List<ScheduledShift>) = shifts.joinToString("|") { "${it.scheduledStart}/${it.scheduledEnd}" }.ifEmpty { "off" }
fun upcomingContent(date: LocalDate, shifts: List<ScheduledShift>, updated: Boolean = false, is24Hour: Boolean = false, locale: Locale = Locale.getDefault()): UpcomingContent {
    val time = DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm a", locale)
    if (shifts.isEmpty()) return UpcomingContent(if (updated) "ShiftHUD · Schedule updated" else "ShiftHUD · Tomorrow off",
        if (updated) "Tomorrow's shifts removed. No shift scheduled." else "No shift scheduled tomorrow.")
    val first = shifts.first().scheduledStart.format(time)
    val lines = shifts.joinToString("\n") { "${date.format(DateTimeFormatter.ofPattern("EEEE", locale))} · ${it.scheduledStart.format(time)}–${it.scheduledEnd.format(time)}${if (it.end.toLocalDate() > it.date) " (+1 day)" else ""}" }
    return UpcomingContent(if (updated) "ShiftHUD · Schedule updated" else "ShiftHUD · Tomorrow at $first",
        if (updated) "Tomorrow now starts at $first${if (shifts.size > 1) " · ${shifts.size} shifts" else ""}" else if (shifts.size > 1) "${shifts.size} shifts scheduled" else lines, lines)
}
fun upcomingPlan(now: Instant, zone: ZoneId, settings: UpcomingSettings, schedule: List<ScheduledShift>, receipt: UpcomingReceipt?, is24Hour: Boolean = false): UpcomingPlan {
    val local = now.atZone(zone)
    val today = local.toLocalDate()
    val tomorrow = today.plusDays(1)
    val shifts = tomorrowShifts(schedule, today)
    val fingerprint = scheduleFingerprint(shifts)
    val previous = receipt?.takeIf { it.date == tomorrow }
    val due = today.atTime(settings.time).atZone(zone).toInstant()
    val nextEvening = tomorrow.atTime(settings.time).atZone(zone).toInstant()
    val changed = previous != null && previous.fingerprint != fingerprint
    val eligible = settings.enabled && (changed || !now.isBefore(due)) && previous?.fingerprint != fingerprint &&
        (shifts.isNotEmpty() || settings.daysOff || previous != null)
    val throttle = previous?.postedAt?.takeIf { it <= now }?.plusSeconds(120)
    val wait = eligible && throttle != null && now < throttle
    return UpcomingPlan(tomorrow, fingerprint,
        if (eligible && !wait) upcomingContent(tomorrow, shifts, changed, is24Hour) else null,
        if (wait) throttle!! else if (now < due && !changed) due else nextEvening)
}
