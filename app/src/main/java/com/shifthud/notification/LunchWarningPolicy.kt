package com.shifthud.notification

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import java.time.Duration
import java.time.Instant

val DEFAULT_WARNING_OFFSETS = setOf(60, 30, 15)
val WARNING_CHOICES = listOf(60, 30, 15, 5, 1)
data class WarningSettings(val threshold: Int = 360, val offsets: Set<Int> = DEFAULT_WARNING_OFFSETS) {
    val validOffsets get() = offsets.filter { it > 0 && it < threshold }.sortedDescending()
    fun boundarySummary(): String = if (validOffsets.isEmpty())
        "No enabled warnings apply to this threshold. Enable a smaller offset below before starting a new shift."
    else "Saved reminder boundaries: " + validOffsets.joinToString(", ") { "${threshold - it}m worked (${it}m before)" }
}
/** Consumed means successfully posted OR intentionally skipped (baseline/superseded), never failed. */
data class WarningLedger(val sessionId: Long, val settings: WarningSettings, val consumed: Set<Int>)
data class WarningDecision(val ledger: WarningLedger?, val offset: Int? = null, val cancel: Boolean = false,
                           val candidateOffset: Int? = null, val reason: String = "inactive", val posted: Boolean = false) {
    fun afterSuccessfulPost(): WarningDecision = if (offset == null || ledger == null) this
        else copy(ledger = ledger.copy(consumed = ledger.consumed + offset), posted = true)
}

fun evaluateLunchWarning(session: WorkSession?, settings: WarningSettings, previous: WarningLedger?,
                         now: Instant, engine: ShiftEngine, allowed: Boolean): WarningDecision {
    if (session == null || session.state == ShiftState.COMPLETE) return WarningDecision(null, cancel = true)
    val sameSession = previous?.sessionId == session.id
    val consumed = if (sameSession) previous!!.consumed else emptySet()
    if (session.state != ShiftState.WORKING || session.lunchStart != null) {
        return WarningDecision(WarningLedger(session.id, settings, consumed + settings.offsets), cancel = true, reason = "lunch_started")
    }
    val elapsed = engine.durations(session, settings.threshold, now).activeWork
    val due = settings.validOffsets.filter { elapsed >= Duration.ofMinutes((settings.threshold - it).toLong()) }.toSet()
    val changed = !sameSession || previous!!.settings != settings
    val reached = elapsed >= Duration.ofMinutes(settings.threshold.toLong())
    val latest = due.minOrNull()
    // Baseline historical settings/first observation; retire obsolete older crossings, not the
    // current candidate. It stays retryable until posting succeeds, even after blocked/failed posts.
    val skipped = if (changed || reached) due else due - setOfNotNull(latest)
    val ledger = WarningLedger(session.id, settings, consumed + skipped)
    val reason = when {
        changed -> "baseline"
        reached -> "threshold_reached"
        settings.validOffsets.isEmpty() -> "no_valid_offsets"
        latest == null -> "not_due"
        latest in consumed -> "already_consumed"
        !allowed -> "blocked"
        else -> "candidate"
    }
    return WarningDecision(ledger, latest?.takeIf { reason == "candidate" },
        changed || !allowed || reached, latest, reason)
}
