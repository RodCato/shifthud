package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.data.local.entity.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ShiftEngineTest {
    private val start = Instant.parse("2026-10-03T09:00:00Z")
    private fun engine(minutes: Long = 0) = ShiftEngine(Clock.fixed(start.plusSeconds(minutes * 60), ZoneOffset.UTC))
    private fun session() = engine().clockIn(42)
    private fun afterLunch(): WorkSession = engine(270).endLunch(engine(240).startLunch(session()))

    @Test fun clockInTransitionsFromNotStarted() {
        assertEquals(ShiftState.NOT_STARTED, engine().state(null))
        val s = session()
        assertEquals(ShiftState.WORKING, s.state)
        assertEquals(start, s.clockIn)
        assertEquals(42L, s.scheduledShiftId)
    }
    @Test fun startLunchTransitionsToOnLunch() {
        val s = engine(240).startLunch(session())
        assertEquals(ShiftState.ON_LUNCH, s.state)
        assertEquals(start.plusSeconds(240 * 60), s.lunchStart)
    }
    @Test fun endLunchTransitionsToWorking() {
        assertEquals(ShiftState.WORKING, afterLunch().state)
        assertEquals(start.plusSeconds(270 * 60), afterLunch().lunchEnd)
    }
    @Test fun clockOutTransitionsToComplete() {
        val s = engine(480).clockOut(afterLunch())
        assertEquals(ShiftState.COMPLETE, s.state)
        assertEquals(start.plusSeconds(480 * 60), s.clockOut)
    }
    @Test fun paidExcludesCompletedLunch() {
        assertEquals(Duration.ofMinutes(450), engine(480).durations(afterLunch(), 360).paid)
    }
    @Test fun storeIncludesLunch() {
        assertEquals(Duration.ofMinutes(480), engine(480).durations(afterLunch(), 360).store)
    }
    @Test fun lunchCountdownUsesActiveWork() {
        val d = engine(330).durations(afterLunch(), 360)
        assertEquals(Duration.ofMinutes(300), d.activeWork)
        assertEquals(Duration.ofMinutes(60), d.lunchRemaining)
    }
    @Test fun ongoingLunchFreezesPaidAndCountdown() {
        val s = engine(240).startLunch(session())
        val d = engine(260).durations(s, 360)
        assertEquals(Duration.ofMinutes(20), d.lunch)
        assertEquals(Duration.ofMinutes(240), d.paid)
        assertEquals(Duration.ofMinutes(120), d.lunchRemaining)
    }
    @Test fun persistedActiveSessionReconstructsWithoutTimer() {
        val persisted = engine(240).startLunch(session()).entity()
        val restored = persisted.copy().model()
        assertEquals(ShiftState.ON_LUNCH, restored.state)
        assertEquals(Duration.ofMinutes(35), engine(275).durations(restored, 360).lunch)
        assertEquals(Duration.ofMinutes(240), engine(275).durations(restored, 360).activeWork)
    }
    @Test fun completedSessionDoesNotKeepTicking() {
        val s = engine(480).clockOut(afterLunch())
        assertEquals(engine(480).durations(s, 360), engine(2000).durations(s, 360))
    }
    @Test fun shiftsSortByDateThenTime() {
        val a = shift("2026-10-04", "04:00", "13:00")
        val b = shift("2026-10-03", "12:00", "20:00")
        val c = shift("2026-10-03", "04:00", "13:00")
        assertEquals(listOf(c, b, a), listOf(a, b, c).chronological())
    }
    @Test fun overnightEndsOnFollowingDate() {
        val s = shift("2026-10-03", "22:00", "06:00")
        assertEquals(LocalDateTime.parse("2026-10-04T06:00"), s.end)
        assertEquals(Duration.ofHours(8), s.duration(ZoneOffset.UTC))
        assertEquals(s, s.entity().model())
    }
    @Test fun overnightAcrossDstUsesActualElapsedTime() {
        val s = shift("2026-10-31", "22:00", "06:00")
        assertEquals(Duration.ofHours(9), s.duration(ZoneId.of("America/Chicago")))
    }
    @Test fun equalTimesMeanTwentyFourHourShift() {
        assertEquals(Duration.ofHours(24), shift("2026-10-03", "04:00", "04:00").duration(ZoneOffset.UTC))
    }
    @Test fun lunchThresholdCanBeExceeded() {
        assertEquals(Duration.ofMinutes(-40), engine(400).durations(session(), 360).lunchRemaining)
    }
    @Test fun clockOutWithoutLunchIsAllowed() {
        val s = engine(120).clockOut(session())
        assertEquals(Duration.ofMinutes(120), engine().durations(s, 360).paid)
    }
    @Test(expected = IllegalArgumentException::class) fun cannotClockOutOnLunch() {
        engine(250).clockOut(engine(240).startLunch(session()))
    }
    @Test(expected = IllegalArgumentException::class) fun cannotStartSecondLunch() { engine(300).startLunch(afterLunch()) }
    @Test(expected = IllegalArgumentException::class) fun cannotEndLunchWhileWorking() { engine(60).endLunch(session()) }
    @Test(expected = IllegalArgumentException::class) fun cannotChangeCompletedSession() { engine(500).startLunch(engine(480).clockOut(afterLunch())) }
    @Test(expected = IllegalArgumentException::class) fun backwardsClockRejectsMutation() { engine(-1).startLunch(session()) }
    @Test fun backwardsClockDoesNotShowNegativeTime() { assertEquals(Duration.ZERO, engine(-1).durations(session(), 360).paid) }
    @Test(expected = IllegalArgumentException::class) fun malformedPersistedStateIsRejected() { session().copy(state = ShiftState.ON_LUNCH) }
    private fun shift(date: String, from: String, to: String) = ScheduledShift(date = LocalDate.parse(date), scheduledStart = LocalTime.parse(from), scheduledEnd = LocalTime.parse(to))
}
