package com.shifthud.widget

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WidgetStatus { OFF_TODAY, TODAY, WORKING, ON_LUNCH, COMPLETE }
enum class WidgetOperation { CLOCK_IN, START_LUNCH, END_LUNCH, REFRESH }
data class WidgetCommand(val operation: WidgetOperation, val sessionId: Long, val date: LocalDate)
data class WidgetState(
    val status: WidgetStatus,
    val headline: String,
    val detail: String,
    val scheduledOut: String? = null,
    val extra: String? = null,
    val actionLabel: String,
    val command: WidgetCommand? = null,
    val destination: String = "Dashboard",
    val updated: String,
)

/** Pure presentation derived from the same engine as the Dashboard, never a second session store. */
class WidgetStateFactory(private val engine: ShiftEngine) {
    fun create(schedule: List<ScheduledShift>, latest: WorkSession?, threshold: Int, now: Instant, zone: ZoneId, locale: Locale): WidgetState {
        val today = now.atZone(zone).toLocalDate()
        val ordered = schedule.chronological()
        val scheduled = ordered.firstOrNull { it.date == today }
        val next = ordered.firstOrNull { it.start.atZone(zone).toInstant() > now && it.id != latest?.scheduledShiftId }
        val time = DateTimeFormatter.ofPattern("h:mm a", locale)
        val date = DateTimeFormatter.ofPattern("EEE MMM d", locale)
        fun range(s: ScheduledShift) = "${s.scheduledStart.format(time)} – ${s.scheduledEnd.format(time)}" + if (s.end.toLocalDate() != s.date) " (+1 day)" else ""
        fun nextLabel() = next?.let { "Next: ${it.date.format(date)} · ${range(it)}" }
        val updated = "Updated ${now.atZone(zone).format(time)}"
        val session = latest?.takeIf { it.state != ShiftState.COMPLETE || it.clockOut?.atZone(zone)?.toLocalDate() == today }
        if (session == null) {
            if (scheduled == null) return WidgetState(WidgetStatus.OFF_TODAY,
                if (next == null) "NO UPCOMING SHIFT" else "OFF TODAY", nextLabel() ?: "Add your schedule in ShiftHUD",
                actionLabel = if (next == null) "ADD SHIFT" else "OPEN SCHEDULE", destination = "Schedule", updated = updated)
            val until = Duration.between(now, scheduled.start.atZone(zone).toInstant())
            return WidgetState(WidgetStatus.TODAY, "TODAY · ${range(scheduled)}",
                if (until.isNegative || until.isZero) "Scheduled start reached" else "Starts in ${until.widgetDuration()}",
                actionLabel = "CLOCK IN", command = WidgetCommand(WidgetOperation.CLOCK_IN, latest?.id ?: 0, today), updated = updated)
        }
        val durations = engine.durations(session, threshold, now)
        val linked = ordered.firstOrNull { it.id == session.scheduledShiftId }
        val out = linked?.let { "Out · ${it.scheduledEnd.format(time)}" + if (it.end.toLocalDate() != it.date) " (+1 day)" else "" } ?: "Unscheduled shift"
        return when (session.state) {
            ShiftState.WORKING -> WidgetState(WidgetStatus.WORKING, "WORKING · ${durations.activeWork.widgetDuration()}",
                if (durations.lunchRemaining.isNegative || durations.lunchRemaining.isZero) "Lunch threshold reached" else "Lunch due in ${durations.lunchRemaining.widgetDuration()}",
                out, if (session.lunchEnd != null) "Lunch taken · ${durations.lunch.widgetDuration()}" else null,
                if (session.lunchStart == null) "START LUNCH" else "OPEN APP", if (session.lunchStart == null) WidgetCommand(WidgetOperation.START_LUNCH, session.id, today) else null, updated = updated)
            ShiftState.ON_LUNCH -> WidgetState(WidgetStatus.ON_LUNCH, "ON LUNCH · ${durations.lunch.widgetDuration()}",
                "Paid · ${durations.paid.widgetDuration()}", out, actionLabel = "END LUNCH", command = WidgetCommand(WidgetOperation.END_LUNCH, session.id, today), updated = updated)
            ShiftState.COMPLETE -> WidgetState(WidgetStatus.COMPLETE, "SHIFT COMPLETE", "Paid · ${durations.paid.widgetDuration()}",
                "Store · ${durations.store.widgetDuration()}", nextLabel(), "OPEN APP", updated = updated)
            ShiftState.NOT_STARTED -> error("NOT_STARTED has no persisted session")
        }
    }
}
internal fun Duration.widgetDuration(): String {
    val minutes = toMinutes().coerceAtLeast(0)
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
