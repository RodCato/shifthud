package com.shifthud.service

import com.shifthud.domain.model.ShiftState
import java.time.Duration

fun requiresActiveRefresh(state: ShiftState?): Boolean =
    state == ShiftState.WORKING || state == ShiftState.ON_LUNCH

/** Align to the next displayed elapsed minute, not to a counter owned by the service. */
fun nextActiveRefreshDelayMillis(elapsed: Duration): Long =
    60_000L - Math.floorMod(elapsed.toMillis().coerceAtLeast(0), 60_000L)

/** App and widget mutations use identical ordering. Even a failed final redraw must stop tracking. */
suspend fun <T> synchronizeActiveRefresh(
    state: ShiftState?,
    mayStartService: Boolean,
    start: () -> Unit,
    stop: () -> Unit,
    refresh: suspend () -> T,
): T {
    if (requiresActiveRefresh(state)) {
        if (mayStartService) start()
        return refresh()
    }
    return try { refresh() } finally { stop() }
}

fun autoLunchRefreshDelayMillis(session: com.shifthud.domain.model.WorkSession, now: java.time.Instant, minuteDelay: Long): Long {
    val target = session.autoLunchEndTarget?.takeIf { session.state == ShiftState.ON_LUNCH } ?: return minuteDelay
    val until = Duration.between(now, target).toMillis()
    // An unsuccessful overdue reconciliation retries at the normal cadence, never a tight loop.
    return if (until > 0) minOf(minuteDelay, until) else minuteDelay
}

/** Upcoming absolute reminder targets share the existing loop; blocked overdue posts retry normally. */
fun shiftEndRefreshDelayMillis(target: java.time.Instant?, now: java.time.Instant, baseDelay: Long): Long {
    val remaining = target?.let { Duration.between(now, it).toMillis() }
    return if (remaining != null && remaining > 0) minOf(baseDelay, remaining) else baseDelay
}
