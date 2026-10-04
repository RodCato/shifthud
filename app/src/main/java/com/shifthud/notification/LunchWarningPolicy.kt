package com.shifthud.notification

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import java.time.Instant

val DEFAULT_WARNING_OFFSETS = setOf(60, 30, 15)
val WARNING_CHOICES = listOf(60, 30, 15, 5, 1)
data class WarningSettings(val threshold: Int = 360, val offsets: Set<Int> = DEFAULT_WARNING_OFFSETS)
/** Delivery metadata only: never elapsed work or a replacement for Room timestamps. */
data class WarningLedger(val sessionId: Long, val settings: WarningSettings, val consumed: Set<Int>)
data class WarningDecision(val ledger: WarningLedger?, val offset: Int? = null, val cancel: Boolean = false)

fun evaluateLunchWarning(session: WorkSession?, settings: WarningSettings, previous: WarningLedger?,
                         now: Instant, engine: ShiftEngine, allowed: Boolean): WarningDecision {
    if (session == null || session.state == ShiftState.COMPLETE) return WarningDecision(null, cancel = true)
    val sameSession = previous?.sessionId == session.id
    val consumed = if (sameSession) previous!!.consumed else emptySet()
    if (session.state != ShiftState.WORKING || session.lunchStart != null) {
        return WarningDecision(WarningLedger(session.id, settings, consumed + settings.offsets), cancel = true)
    }
    val elapsed = engine.durations(session, settings.threshold, now).activeWork.toMinutes()
    val due = settings.offsets.filter { it < settings.threshold && elapsed >= settings.threshold - it }.toSet()
    val changed = !sameSession || previous!!.settings != settings
    val ledger = WarningLedger(session.id, settings, consumed + due)
    // First observation/settings changes establish a baseline, never replay historical boundaries.
    // Mark every crossed boundary consumed, but offer only the latest still-relevant one.
    val latest = due.minOrNull()
    val offset = latest?.takeIf { !changed && allowed && it !in consumed && elapsed < settings.threshold }
    return WarningDecision(ledger, offset, changed || !allowed || elapsed >= settings.threshold)
}
