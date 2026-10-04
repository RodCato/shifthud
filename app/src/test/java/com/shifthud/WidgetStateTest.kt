package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.widget.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class WidgetStateTest {
    private val day = LocalDate.of(2026, 10, 5)
    private val start = Instant.parse("2026-10-05T04:00:00Z")
    private val factory = WidgetStateFactory(ShiftEngine(Clock.fixed(start, ZoneOffset.UTC)))
    private fun shift(id: Long = 1, date: LocalDate = day, from: Int = 4, to: Int = 13) = ScheduledShift(id, date, LocalTime.of(from, 0), LocalTime.of(to, 0))
    private fun session() = WorkSession(id = 8, scheduledShiftId = 1, clockIn = start)
    private fun state(schedule: List<ScheduledShift> = listOf(shift()), session: WorkSession? = null, minutes: Long = 0, threshold: Int = 360) =
        factory.create(schedule, session, threshold, start.plusSeconds(minutes * 60), ZoneOffset.UTC, Locale.US)

    @Test fun offTodayWithNoUpcomingOffersAdd() {
        val s = state(emptyList())
        assertEquals(WidgetStatus.OFF_TODAY, s.status)
        assertEquals("NO UPCOMING SHIFT", s.headline)
        assertEquals("ADD SHIFT", s.actionLabel)
        assertEquals("Schedule", s.destination)
    }
    @Test fun nextShiftIsChronologicalAndExcludesPast() {
        val s = state(listOf(shift(date = day.plusDays(3)), shift(date = day.minusDays(1)), shift(date = day.plusDays(1), from = 9), shift(date = day.plusDays(1), from = 4)))
        assertEquals("OFF TODAY", s.headline)
        assertEquals("Next: Tue Oct 6 · 4:00 AM – 1:00 PM", s.detail)
        assertEquals("OPEN SCHEDULE", s.actionLabel)
    }
    @Test fun todayBeforeStartShowsCountdownAndClockIn() {
        val s = state(minutes = -90)
        assertEquals(WidgetStatus.TODAY, s.status)
        assertEquals("Starts in 1h 30m", s.detail)
        assertEquals(WidgetCommand(WidgetOperation.CLOCK_IN, 0, day), s.command)
    }
    @Test fun afterScheduledStartDoesNotShowNegativeCountdown() { assertEquals("Scheduled start reached", state(minutes = 30).detail) }
    @Test fun workingUsesActiveTimeAndThreshold() {
        val s = state(session = session(), minutes = 277)
        assertEquals(WidgetStatus.WORKING, s.status)
        assertEquals("WORKING · 4h 37m", s.headline)
        assertEquals("Lunch due in 1h 23m", s.detail)
        assertEquals(WidgetOperation.START_LUNCH, s.command!!.operation)
    }
    @Test fun thresholdPreferenceChangesCountdown() { assertEquals("Lunch due in 23m", state(session = session(), minutes = 277, threshold = 300).detail) }
    @Test fun thresholdReachedDoesNotShowNegativeMinutes() { assertEquals("Lunch threshold reached", state(session = session(), minutes = 400).detail) }
    @Test fun scheduledOutUsesFriendlyTime() { assertEquals("Out · 1:00 PM", state(session = session()).scheduledOut) }
    @Test fun activeOvernightSurvivesCalendarRollover() {
        val s = state(listOf(shift(date = day.minusDays(1), from = 23, to = 4)), session().copy(clockIn = start.minusSeconds(5 * 3600)))
        assertEquals(WidgetStatus.WORKING, s.status)
        assertEquals("Out · 4:00 AM (+1 day)", s.scheduledOut)
    }
    @Test fun lunchStateReconstructsFromPersistedTimestamps() {
        val persisted = session().copy(lunchStart = start.plusSeconds(240 * 60), state = ShiftState.ON_LUNCH)
        val s = state(session = persisted, minutes = 263)
        assertEquals("ON LUNCH · 23m", s.headline)
        assertEquals("Paid · 4h 0m", s.detail)
        assertEquals(WidgetOperation.END_LUNCH, s.command!!.operation)
    }
    @Test fun workAfterLunchExcludesLunchAndDoesNotOfferSecondLunch() {
        val s = state(session = session().copy(lunchStart = start.plusSeconds(240 * 60), lunchEnd = start.plusSeconds(270 * 60)), minutes = 300)
        assertEquals("WORKING · 4h 30m", s.headline)
        assertEquals("Lunch due in 1h 30m", s.detail)
        assertEquals("OPEN APP", s.actionLabel)
        assertNull(s.command)
    }
    @Test fun completeTotalsFreezeAndShowNextShift() {
        val complete = session().copy(lunchStart = start.plusSeconds(240 * 60), lunchEnd = start.plusSeconds(270 * 60), clockOut = start.plusSeconds(480 * 60), state = ShiftState.COMPLETE)
        val s = state(listOf(shift(), shift(id = 2, date = day.plusDays(2))), complete, 600)
        assertEquals(WidgetStatus.COMPLETE, s.status)
        assertEquals("Paid · 7h 30m", s.detail)
        assertEquals("Store · 8h 0m", s.scheduledOut)
        assertTrue(s.extra!!.startsWith("Next: Wed Oct 7"))
    }
    @Test fun completeCanShowAnotherUpcomingShiftOnSameDay() {
        val complete = session().copy(clockOut = start.plusSeconds(8 * 3600), state = ShiftState.COMPLETE)
        val s = state(listOf(shift(), shift(id = 2, from = 18, to = 22)), complete, 600)
        assertEquals("Next: Mon Oct 5 · 6:00 PM – 10:00 PM", s.extra)
    }
    @Test fun yesterdaysCompleteDoesNotHideToday() {
        val complete = session().copy(clockIn = start.minusSeconds(86400), clockOut = start.minusSeconds(3600 * 20), state = ShiftState.COMPLETE)
        assertEquals(WidgetStatus.TODAY, state(session = complete).status)
        assertEquals(8L, state(session = complete).command!!.sessionId)
    }
    @Test fun deletedAssociationRemainsUsable() { assertEquals("Unscheduled shift", state(emptyList(), session().copy(scheduledShiftId = null)).scheduledOut) }
    @Test fun minutesOnlyNeverIncludeSeconds() { assertEquals("23m", Duration.ofSeconds(23 * 60 + 59).widgetDuration()) }
}
