package com.shifthud.notification

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import java.time.Duration
import java.time.Instant

val SNOOZE_CHOICES = listOf(5, 10, 15, 20)
/** One event per session: generation zero is the threshold; later generations are explicit snoozes. */
data class LunchAttention(val sessionId: Long, val generation: Long = 0,
                          val targetActiveMillis: Long? = null, val posted: Boolean = false) {
    val receipt get() = "$sessionId:attention:$generation"
}
data class AttentionDecision(val state: LunchAttention?, val due: Boolean = false) {
    fun acknowledged() = copy(state = state?.copy(posted = true))
}
fun evaluateLunchAttention(session: WorkSession?, previous: LunchAttention?, threshold: Int,
                           now: Instant, engine: ShiftEngine): AttentionDecision {
    if (session == null || session.state != ShiftState.WORKING || session.lunchStart != null) return AttentionDecision(null)
    val state = previous?.takeIf { it.sessionId == session.id } ?: LunchAttention(session.id)
    val target = state.targetActiveMillis ?: Duration.ofMinutes(threshold.toLong()).toMillis()
    return AttentionDecision(state, !state.posted && engine.durations(session, threshold, now).activeWork.toMillis() >= target)
}
fun snoozeLunchAttention(session: WorkSession?, state: LunchAttention?, receipt: String,
                         threshold: Int, minutes: Int, now: Instant, engine: ShiftEngine): LunchAttention? {
    require(minutes in SNOOZE_CHOICES)
    if (session == null || session.id != state?.sessionId || session.state != ShiftState.WORKING ||
        session.lunchStart != null || !state.posted || state.receipt != receipt) return null
    val active = engine.durations(session, threshold, now).activeWork
    if (active < Duration.ofMinutes(threshold.toLong())) return null
    return LunchAttention(session.id, state.generation + 1, active.plusMinutes(minutes.toLong()).toMillis())
}
