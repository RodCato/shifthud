package com.shifthud.notification

import com.shifthud.data.repository.ShiftSnapshot
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.ui.completedLunch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LunchAction { START_LUNCH, END_LUNCH }
data class ShiftNotificationState(val title: String, val content: String, val secondary: List<String>, val action: LunchAction?)
fun Duration.notificationDuration(): String {
    val minutes = toMinutes().coerceAtLeast(0)
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
class ShiftNotificationStateFactory(private val engine: ShiftEngine) {
    fun create(snapshot: ShiftSnapshot, threshold: Int, now: Instant, zone: ZoneId, locale: Locale, use24Hour: Boolean): ShiftNotificationState? {
        val session = snapshot.session ?: return null
        if (session.state != ShiftState.WORKING && session.state != ShiftState.ON_LUNCH) return null
        val d = engine.durations(session, threshold, now)
        val lunch = completedLunch(session, zone, locale, use24Hour)
        val time = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
        val out = snapshot.schedule.firstOrNull { it.id == session.scheduledShiftId }?.let {
            "Out · ${it.scheduledEnd.format(time)}" + if (it.end.toLocalDate() != it.date) " (+1 day)" else ""
        }
        return when (session.state) {
            ShiftState.ON_LUNCH -> ShiftNotificationState("ShiftHUD · On lunch", "Lunch ${d.lunch.notificationDuration()} · Paid ${d.paid.notificationDuration()}", listOfNotNull(out), LunchAction.END_LUNCH)
            else -> ShiftNotificationState("ShiftHUD · Working",
                "Worked ${d.activeWork.notificationDuration()}" + if (lunch != null) "" else if (d.lunchRemaining.isNegative || d.lunchRemaining.isZero) " · Lunch threshold reached" else " · Lunch due in ${d.lunchRemaining.notificationDuration()}",
                listOfNotNull(lunch?.let { "Lunch taken · ${it.interval} · ${it.durationLabel}" }, out),
                if (session.lunchStart == null) LunchAction.START_LUNCH else null)
        }
    }
}

/** Current remaining time, never an obsolete offset label after delayed delivery. */
fun warningTitle(remaining: Duration): String {
    val minutes = ((remaining.toMillis().coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
    return if (minutes == 60L) "Lunch due in 1 hour" else "Lunch due in $minutes ${if (minutes == 1L) "minute" else "minutes"}"
}
