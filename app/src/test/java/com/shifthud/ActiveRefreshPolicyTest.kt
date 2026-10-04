package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.service.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ActiveRefreshPolicyTest {
    @Test fun workingRequiresActiveRefresh() { assertTrue(requiresActiveRefresh(ShiftState.WORKING)) }
    @Test fun lunchRequiresActiveRefresh() { assertTrue(requiresActiveRefresh(ShiftState.ON_LUNCH)) }
    @Test fun completedDoesNotRequireActiveRefresh() { assertFalse(requiresActiveRefresh(ShiftState.COMPLETE)) }
    @Test fun absentOrNotStartedDoesNotRequireActiveRefresh() {
        assertFalse(requiresActiveRefresh(null))
        assertFalse(requiresActiveRefresh(ShiftState.NOT_STARTED))
    }
    @Test fun activeStartsBeforeImmediateRefresh() = runBlocking {
        val events = mutableListOf<String>()
        synchronizeActiveRefresh(ShiftState.WORKING, true, { events += "start" }, { events += "stop" }) { events += "refresh" }
        assertEquals(listOf("start", "refresh"), events)
    }
    @Test fun clockOutRefreshesBeforeStoppingLifecycle() = runBlocking {
        val events = mutableListOf<String>()
        synchronizeActiveRefresh(ShiftState.COMPLETE, true, { events += "start" }, { events += "stop" }) { events += "final refresh" }
        assertEquals(listOf("final refresh", "stop"), events)
    }
    @Test fun absentSessionAlsoStopsLifecycle() = runBlocking {
        val events = mutableListOf<String>()
        synchronizeActiveRefresh(null, true, { events += "start" }, { events += "stop" }) { events += "refresh" }
        assertEquals(listOf("refresh", "stop"), events)
    }
    @Test fun failedFinalRefreshStillStops() = runBlocking {
        var stopped = false
        assertTrue(runCatching {
            synchronizeActiveRefresh(ShiftState.COMPLETE, true, {}, { stopped = true }) { error("Host unavailable") }
        }.isFailure)
        assertTrue(stopped)
    }
    @Test fun backgroundFallbackDoesNotAttemptForbiddenServiceStart() = runBlocking {
        var refreshed = false
        synchronizeActiveRefresh(ShiftState.WORKING, false, { fail("Background start") }, { fail("Stopped active session") }) { refreshed = true }
        assertTrue(refreshed)
    }
    @Test fun nextTickAlignsWithElapsedMinutesWithoutPerSecondPolling() {
        assertEquals(60_000L, nextActiveRefreshDelayMillis(Duration.ZERO))
        assertEquals(60_000L, nextActiveRefreshDelayMillis(Duration.ofMinutes(5)))
        assertEquals(30_000L, nextActiveRefreshDelayMillis(Duration.ofSeconds(30)))
        assertEquals(45_000L, nextActiveRefreshDelayMillis(Duration.ofSeconds(315)))
    }
    @Test fun delayedRefreshImmediatelyCatchesUpFromTimestamps() {
        val at = Instant.parse("2026-10-04T16:00:17Z")
        val engine = ShiftEngine(Clock.fixed(at, ZoneOffset.UTC))
        val session = engine.clockIn()
        assertEquals(Duration.ZERO, engine.durations(session, 360, at).activeWork)
        assertEquals(Duration.ofMinutes(5), engine.durations(session, 360, at.plusSeconds(300)).activeWork)
        assertEquals(at, session.clockIn)
        assertNull(session.clockOut)
        assertEquals(ShiftState.WORKING, session.state)
    }
    @Test fun lunchAndResumedWorkUsePersistedEventsForTickAlignment() {
        val at = Instant.parse("2026-10-04T16:00:17Z")
        val engine = ShiftEngine(Clock.fixed(at, ZoneOffset.UTC))
        val onLunch = WorkSession(clockIn = at, lunchStart = at.plusSeconds(3600), state = ShiftState.ON_LUNCH)
        val lunch = engine.durations(onLunch, 360, at.plusSeconds(3690))
        assertEquals(30_000L, nextActiveRefreshDelayMillis(lunch.lunch))
        assertEquals(Duration.ofHours(1), lunch.activeWork)
        val working = onLunch.copy(lunchEnd = at.plusSeconds(3900), state = ShiftState.WORKING)
        val resumed = engine.durations(working, 360, at.plusSeconds(3945))
        assertEquals(15_000L, nextActiveRefreshDelayMillis(resumed.activeWork))
        assertEquals(Duration.ofSeconds(3645), resumed.activeWork)
    }
}
