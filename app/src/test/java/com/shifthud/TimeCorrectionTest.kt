package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.*
import com.shifthud.notification.*
import com.shifthud.widget.*
import com.shifthud.data.repository.ShiftSnapshot
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class TimeCorrectionTest {
    private val day = Instant.parse("2026-10-05T00:00:00Z")
    private fun at(minutes: Long) = day.plusSeconds(minutes * 60)
    private val now = at(900)
    private val engine = ShiftEngine(Clock.fixed(now, ZoneOffset.UTC))
    private val complete = WorkSession(id = 1, clockIn = at(255), lunchStart = at(600), lunchEnd = at(660), clockOut = at(780), state = ShiftState.COMPLETE)
    @Test fun unchangedManualEventDoesNotResetReminderRevision() {
        assertEquals(complete,correctTime(complete,TimeEvent.CLOCK_IN,complete.clockIn,now))
        val auto=complete.copy(lunchEndAutomatic=true)
        assertFalse(correctTime(auto,TimeEvent.LUNCH_END,auto.lunchEnd!!,now).lunchEndAutomatic)
    }
    @Test fun clockInEarlierRecalculatesPaidStoreAndActive() {
        val edited = correctTime(complete, TimeEvent.CLOCK_IN, at(240), now)
        val d = engine.durations(edited, 360)
        assertEquals(480, d.paid.toMinutes()); assertEquals(540, d.store.toMinutes()); assertEquals(d.paid, d.activeWork)
    }
    @Test fun clockInLaterReducesPaid() {
        assertEquals(450, engine.durations(correctTime(complete, TimeEvent.CLOCK_IN, at(270), now), 360).paid.toMinutes())
    }
    @Test fun lunchStartCorrectionRecalculatesLunch() {
        assertEquals(45, engine.durations(correctTime(complete, TimeEvent.LUNCH_START, at(615), now), 360).lunch.toMinutes())
    }
    @Test fun automaticLunchEndCorrectionClearsAutoAndUsesOnlyCorrectedTime() {
        val edited = correctTime(complete.copy(lunchEndAutomatic = true, autoLunchMinutes = 60), TimeEvent.LUNCH_END, at(645), now)
        assertFalse(edited.lunchEndAutomatic)
        assertEquals(480, engine.durations(edited, 360).paid.toMinutes())
        assertEquals(45, engine.durations(edited, 360).lunch.toMinutes())
    }
    @Test fun clockOutCorrectionUpdatesCompletedStore() {
        assertEquals(540, engine.durations(correctTime(complete, TimeEvent.CLOCK_OUT, at(795), now), 360).store.toMinutes())
    }
    @Test fun invalidChronologyAndFutureAreRejected() {
        listOf(TimeEvent.CLOCK_IN to at(601), TimeEvent.LUNCH_START to at(254), TimeEvent.LUNCH_END to at(599), TimeEvent.CLOCK_OUT to at(659), TimeEvent.CLOCK_OUT to at(901)).forEach { (e,t) ->
            assertTrue(runCatching { correctTime(complete,e,t,now) }.isFailure)
        }
        val activeFuture = WorkSession(clockIn=at(240), lunchStart=at(300), lunchEnd=at(400))
        assertTrue(runCatching { correctTime(activeFuture,TimeEvent.CLOCK_IN,at(230),at(350)) }.isFailure)
    }
    @Test fun missingEventsCannotBeFabricated() {
        assertTrue(runCatching { correctTime(WorkSession(clockIn = at(240)), TimeEvent.LUNCH_END, at(645), now) }.isFailure)
    }
    @Test fun overnightDatesRemainOrdered() {
        val overnight = WorkSession(clockIn = at(1380), clockOut = at(1740), state = ShiftState.COMPLETE)
        assertEquals(420, engine.durations(correctTime(overnight, TimeEvent.CLOCK_IN, at(1320), at(1800)), 360).paid.toMinutes())
    }
    @Test fun dstGapRejectedAndOriginalOverlapOffsetPreserved() {
        val zone = ZoneId.of("America/Chicago")
        assertTrue(runCatching { correctedInstant(LocalDate.of(2026,3,8), LocalTime.of(2,30), zone, day) }.isFailure)
        val later = Instant.parse("2026-11-01T07:30:00Z")
        assertEquals(later, correctedInstant(LocalDate.of(2026,11,1), LocalTime.of(1,30), zone, later))
    }
    @Test fun completedWidgetAndWorkingNotificationUseCorrectedTimes() {
        val edited = correctTime(complete, TimeEvent.CLOCK_IN, at(240), now)
        val widget = WidgetStateFactory(engine).create(emptyList(), edited, 360, now, ZoneOffset.UTC, Locale.US)
        assertEquals("Paid · 8h 0m", widget.detail)
        val active = edited.copy(clockOut = null, state = ShiftState.WORKING)
        val notification = ShiftNotificationStateFactory(engine).create(ShiftSnapshot(emptyList(), active), 360, at(780), ZoneOffset.UTC, Locale.US, false)!!
        assertEquals("Worked 8h 0m", notification.content)
        assertFalse(notification.content.contains("Lunch due"))
    }
    @Test fun correctionCrossingEarlyBoundariesBaselinesWithoutBurst() {
        val old = WorkSession(id = 1, clockIn = at(300))
        val settings = WarningSettings(360)
        val initial = evaluateLunchWarning(old, settings, null, at(580), engine, true)
        val edited = correctTime(old, TimeEvent.CLOCK_IN, at(240), at(580))
        val correction = evaluateLunchWarning(edited, settings, initial.ledger, at(580), engine, true)
        assertNull(correction.offset); assertEquals(setOf(60,30), correction.ledger!!.consumed)
        assertEquals(15, evaluateLunchWarning(edited,settings,correction.ledger,at(585),engine,true).offset)
    }
    @Test fun correctionSkipsCrossedThresholdWithoutFalselyAcknowledgingPost() {
        val old = WorkSession(id=1,clockIn=at(300))
        val edited = correctTime(old,TimeEvent.CLOCK_IN,at(200),at(580))
        val d = evaluateLunchAttention(edited,LunchAttention(1),360,at(580),engine)
        assertFalse(d.due); assertTrue(d.corrected); assertTrue(d.state!!.skipped); assertFalse(d.state!!.posted)
        assertFalse(evaluateLunchAttention(edited,d.state,360,at(600),engine).due)
    }
    @Test fun correctionPreservesFutureSnoozeButRejectsOldReceipt() {
        val old = WorkSession(id=1,clockIn=at(240))
        val state = LunchAttention(1,1,Duration.ofMinutes(400).toMillis())
        val edited = correctTime(old,TimeEvent.CLOCK_IN,at(230),at(600))
        val d = evaluateLunchAttention(edited,state,360,at(600),engine)
        assertFalse(d.due); assertEquals(state.targetActiveMillis,d.state!!.targetActiveMillis)
        assertNotEquals(state.receipt,d.state!!.receipt)
        assertTrue(evaluateLunchAttention(edited,d.state,360,at(630),engine).due)
    }
    @Test fun correctionCrossingPendingSnoozeRetiresIt() {
        val edited = WorkSession(id=1,clockIn=at(200),correctionRevision=1)
        val d = evaluateLunchAttention(edited,LunchAttention(1,1,Duration.ofMinutes(370).toMillis()),360,at(600),engine)
        assertFalse(d.due); assertTrue(d.state!!.skipped)
    }
}
