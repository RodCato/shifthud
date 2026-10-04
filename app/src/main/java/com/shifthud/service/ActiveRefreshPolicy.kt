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
