package com.shifthud.notification

import com.shifthud.domain.weekly.*
import com.shifthud.domain.model.ShiftState
import java.time.*

/** Delivery metadata only; no elapsed-time counters or payroll totals. */
data class WeeklyWarningLedger(val weekStart: LocalDate, val settings: WeeklyTargetSettings,
    val consumed: Set<Int>, val correctionKey: String) {
    fun receipt(boundary: Int) = "$weekStart:${settings.targetMinutes}:$boundary"
}
data class WeeklyWarningDecision(val ledger: WeeklyWarningLedger, val boundary: Int? = null, val cancel: Boolean = false,
    val nextTarget: Instant? = null) {
    fun acknowledged() = copy(ledger = ledger.copy(consumed = ledger.consumed + requireNotNull(boundary)))
}
fun evaluateWeeklyWarning(progress: WeeklyTargetProgress, settings: WeeklyTargetSettings,
                          previous: WeeklyWarningLedger?, now: Instant, baseline: Boolean = false): WeeklyWarningDecision {
    val boundaries = (settings.warnings + 0).filter { it < settings.targetMinutes }.toSet()
    val passed = boundaries.filter { progress.paid >= settings.target.minusMinutes(it.toLong()) }.toSet()
    val sameWeek = previous?.weekStart == progress.week.start && previous.settings.targetMinutes == settings.targetMinutes
    val changed = baseline || !sameWeek || previous!!.settings != settings || previous.correctionKey != progress.correctionKey
    val active = progress.active != null
    var consumed = if (sameWeek) previous!!.consumed else emptySet()
    if (changed || !active || !settings.enabled) consumed = consumed + passed
    val candidate = if (settings.enabled && active && !changed) (passed - consumed).minOrNull() else null
    // A delayed observation delivers the most relevant boundary, never a burst of old alerts.
    if (candidate != null) consumed = consumed + (passed - candidate)
    val ledger = WeeklyWarningLedger(progress.week.start, settings, consumed, progress.correctionKey)
    val next = if (settings.enabled && progress.active?.state == ShiftState.WORKING)
        boundaries.filter { it !in consumed && it !in passed }.map { settings.target.minusMinutes(it.toLong()).minus(progress.paid) }
            .minOrNull()?.let { now.plus(it) } else null
    return WeeklyWarningDecision(ledger, candidate, !settings.enabled || !active || changed, next)
}

/** Standard concise Android text suitable for watch notification mirroring; no watch actions required. */
fun weeklyWarningText(decision: WeeklyWarningDecision, progress: WeeklyTargetProgress): Pair<String, String> {
    val remainingMinutes = ((progress.remaining.toMillis() + 59_999) / 60_000).coerceAtLeast(0)
    val title = when {
        progress.remaining.isZero -> "Weekly target reached"
        decision.boundary == 120 -> "Weekly target approaching"
        else -> "Weekly target in $remainingMinutes minutes"
    }
    val paid = progress.paid.notificationDuration()
    val body = if (progress.remaining.isZero) "$paid paid this week"
        else "$paid worked · ${Duration.ofMinutes(remainingMinutes).notificationDuration()} remaining"
    return title to body
}
