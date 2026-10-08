package com.shifthud.notification

import com.shifthud.domain.model.*
import com.shifthud.data.repository.ShiftSnapshot
import java.time.*

val SHIFT_END_LEADS = listOf(5, 10, 15, 20, 30)
val SHIFT_END_SNOOZES = listOf(5, 10, 15)
data class ShiftEndSettings(val enabled: Boolean = true, val leadMinutes: Int = 15, val snoozeMinutes: Int = 10)
data class ShiftEndDelivery(val sessionId: Long, val end: Instant, val lead: Int, val target: Instant,
    val generation: Long = 0, val posted: Boolean = false, val skipped: Boolean = false) {
    val receipt: String get() = "$sessionId:${end.toEpochMilli()}:$lead:${target.toEpochMilli()}:$generation"
}
data class ShiftEndDecision(val state: ShiftEndDelivery?, val due: Boolean = false, val cancel: Boolean = false)

fun scheduledReminderEnd(snapshot: ShiftSnapshot, zone: ZoneId): Instant? {
    val session = snapshot.session ?: return null
    if (session.state !in listOf(ShiftState.WORKING, ShiftState.ON_LUNCH)) return null
    return snapshot.schedule.firstOrNull { it.id == session.scheduledShiftId }?.end?.atZone(zone)?.toInstant()
}

/** Absolute instants only. A changed, already-past boundary is baselined, never replayed. */
fun evaluateShiftEnd(snapshot: ShiftSnapshot, settings: ShiftEndSettings, previous: ShiftEndDelivery?, now: Instant, zone: ZoneId): ShiftEndDecision {
    val end = scheduledReminderEnd(snapshot, zone)
    if (!settings.enabled || end == null) return ShiftEndDecision(null, cancel = true)
    val sessionId = snapshot.session!!.id
    val same = previous?.sessionId == sessionId && previous.end == end && previous.lead == settings.leadMinutes
    val target = end.minusSeconds(settings.leadMinutes * 60L)
    val state = if (same) previous!! else ShiftEndDelivery(sessionId, end, settings.leadMinutes, target,
        skipped = previous?.sessionId == sessionId && !target.isAfter(now))
    // Recovery delivers one recent event, not an hours-old wrap-up reminder.
    val expired = !state.posted && now.isAfter(state.target.plusSeconds(30 * 60L))
    val reconciled = if (expired) state.copy(skipped = true) else state
    return ShiftEndDecision(reconciled, !reconciled.posted && !reconciled.skipped && !now.isBefore(reconciled.target),
        cancel = !same || reconciled.skipped)
}

fun snoozeShiftEnd(snapshot: ShiftSnapshot, settings: ShiftEndSettings, state: ShiftEndDelivery?, receipt: String, now: Instant, zone: ZoneId): ShiftEndDelivery? {
    if (!settings.enabled || state == null || !state.posted || state.receipt != receipt ||
        state.sessionId != snapshot.session?.id || state.end != scheduledReminderEnd(snapshot, zone) || state.lead != settings.leadMinutes) return null
    return state.copy(target = now.plusSeconds(settings.snoozeMinutes * 60L), generation = state.generation + 1, posted = false, skipped = false)
}

fun shiftEndTitle(state: ShiftEndDelivery, now: Instant): String = when {
    !now.isBefore(state.end) -> "Scheduled shift ended"
    state.generation > 0 -> "Shift ending soon"
    else -> "Shift ends in ${((Duration.between(now, state.end).seconds + 59) / 60).coerceAtLeast(1)} minutes"
}
