package com.shifthud

import com.shifthud.data.repository.ShiftSnapshot
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class ShiftNotificationStateTest {
    private val at = Instant.parse("2026-10-05T04:00:00Z")
    private val engine = ShiftEngine(Clock.fixed(at, ZoneOffset.UTC))
    private val factory = ShiftNotificationStateFactory(engine)
    private val session = WorkSession(id = 1, scheduledShiftId = 2, clockIn = at)
    private val shift = ScheduledShift(2, LocalDate.of(2026,10,5), LocalTime.of(4,0), LocalTime.of(13,0))
    private fun state(s: WorkSession? = session, minutes: Long = 277, use24: Boolean = false) =
        factory.create(ShiftSnapshot(listOf(shift), s), 360, at.plusSeconds(minutes * 60), ZoneOffset.UTC, Locale.US, use24)
    @Test fun workingPreLunchContentAndActions() {
        val s = state()!!
        assertEquals("ShiftHUD · Working", s.title)
        assertEquals("Worked 4h 37m · Lunch due in 1h 23m", s.content)
        assertEquals(listOf("Out · 1:00 PM"), s.secondary)
        assertEquals(LunchAction.START_LUNCH, s.action)
    }
    @Test fun currentLunchAndPaidPause() {
        val s = state(session.copy(lunchStart = at.plusSeconds(277 * 60), state = ShiftState.ON_LUNCH), 300)!!
        assertEquals("ShiftHUD · On lunch", s.title)
        assertEquals("Lunch 23m · Paid 4h 37m", s.content)
        assertEquals(LunchAction.END_LUNCH, s.action)
    }
    @Test fun completedLunchHidesCountdownAndResumesPaidTime() {
        val s = state(session.copy(lunchStart = at.plusSeconds(277 * 60), lunchEnd = at.plusSeconds(300 * 60)), 301)!!
        assertEquals("Worked 4h 38m", s.content)
        assertEquals("Lunch taken · 8:37 AM – 9:00 AM · 23m", s.secondary.first())
        assertFalse((s.content + s.secondary).contains("due"))
        assertNull(s.action)
    }
    @Test fun completeRemovesOngoingPresentation() { assertNull(state(session.copy(clockOut = at.plusSeconds(3600), state = ShiftState.COMPLETE))) }
    @Test fun absentSessionHasNoNotification() { assertNull(state(null)) }
    @Test fun device24HourPreferenceApplies() { assertEquals(listOf("Out · 13:00"), state(use24 = true)!!.secondary) }
    @Test fun renderIsDeterministicAndDoesNotMutateTimestampState() {
        val before = session.copy()
        assertEquals(state(), state())
        assertEquals(before, session)
    }
    @Test fun reachedThresholdUsesNeutralWording() { assertEquals("Worked 6h 0m · Lunch threshold reached", state(minutes = 360)!!.content) }
}
