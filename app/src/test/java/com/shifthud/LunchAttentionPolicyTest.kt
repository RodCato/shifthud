package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LunchAttentionPolicyTest {
    private val start = Instant.parse("2026-10-05T08:00:00Z")
    private val engine = ShiftEngine(Clock.fixed(start, ZoneOffset.UTC))
    private val session = WorkSession(id = 1, clockIn = start)
    private fun evaluate(seconds: Long, previous: LunchAttention? = null, s: WorkSession? = session) =
        evaluateLunchAttention(s, previous, 5, start.plusSeconds(seconds), engine)
    private fun snooze(seconds: Long, state: LunchAttention = LunchAttention(1, posted = true), minutes: Int = 10) =
        snoozeLunchAttention(session, state, state.receipt, 5, minutes, start.plusSeconds(seconds), engine)!!
    @Test fun thresholdCrossingAlertsOnceWithoutExactTick() {
        val before = evaluate(299); assertFalse(before.due)
        val crossed = evaluate(301, before.state); assertTrue(crossed.due)
        assertFalse(evaluate(360, crossed.acknowledged().state).due)
    }
    @Test fun delayedFirstObservationStillOffersOneThresholdAlert() {
        assertTrue(evaluate(900).due)
        assertFalse(evaluate(960, evaluate(900).acknowledged().state).due)
    }
    @Test fun allConfiguredDurationsUseCurrentActiveWorkWithMillisecondPrecision() {
        SNOOZE_CHOICES.forEach { minutes ->
            val result = snoozeLunchAttention(session, LunchAttention(1, posted = true), "1:attention:0", 5,
                minutes, start.plusMillis(304123), engine)!!
            assertEquals(304123L + minutes * 60000L, result.targetActiveMillis)
        }
    }
    @Test fun snoozeUsesDurationNotWallClockEpoch() {
        assertEquals(904000L, snooze(304).targetActiveMillis)
        assertEquals(start, session.clockIn); assertNull(session.lunchStart); assertNull(session.clockOut)
    }
    @Test fun expirationCrossesOnce() {
        val state = snooze(304)
        assertFalse(evaluate(903, state).due)
        val due = evaluate(905, state); assertTrue(due.due)
        assertFalse(evaluate(1200, due.acknowledged().state).due)
    }
    @Test fun repeatedSnoozeReplacesTargetUsingCurrentWork() {
        val first = snooze(304)
        val delivered = evaluate(905, first).acknowledged().state!!
        val second = snooze(910, delivered, 5)
        assertEquals(1210000L, second.targetActiveMillis)
        assertEquals(2L, second.generation)
        assertNotEquals(first.receipt, second.receipt)
    }
    @Test fun repeatedOldActionDoesNotExtendTarget() {
        val state = snooze(304)
        assertNull(snoozeLunchAttention(session, state, "1:attention:0", 5, 10, start.plusSeconds(305), engine))
    }
    @Test fun lunchStartClearsAndSuppressesFutureAlert() {
        val lunch = session.copy(lunchStart = start.plusSeconds(305), state = ShiftState.ON_LUNCH)
        assertNull(evaluate(5000, snooze(304), lunch).state)
    }
    @Test fun completedLunchNeverAlertsAgain() {
        val lunch = session.copy(lunchStart = start.plusSeconds(305), lunchEnd = start.plusSeconds(605))
        assertNull(evaluate(5000, snooze(304), lunch).state)
    }
    @Test fun clockOutAndMissingSessionClearState() {
        val ended = session.copy(clockOut = start.plusSeconds(400), state = ShiftState.COMPLETE)
        assertNull(evaluate(5000, snooze(304), ended).state)
        assertNull(evaluate(5000, snooze(304), null).state)
    }
    @Test fun staleSessionCannotSnoozeCurrentSession() {
        assertNull(snoozeLunchAttention(session.copy(id = 2), LunchAttention(1, posted = true), "1:attention:0", 5, 10, start.plusSeconds(304), engine))
    }
    @Test fun undeliveredOrPreThresholdEventsCannotBeSnoozed() {
        assertNull(snoozeLunchAttention(session, LunchAttention(1), "1:attention:0", 5, 10, start.plusSeconds(304), engine))
        assertNull(snoozeLunchAttention(session, LunchAttention(1, posted = true), "1:attention:0", 5, 10, start.plusSeconds(299), engine))
    }
    @Test fun recoveryKeepsTargetAndDelayedRecoveryOffersOnlyOneEvent() {
        val state = snooze(304)
        assertEquals(state, evaluate(800, state).state)
        val recovered = evaluate(9000, state)
        assertTrue(recovered.due); assertEquals(state.receipt, recovered.state!!.receipt)
        assertFalse(evaluate(9060, recovered.acknowledged().state).due)
    }
}
