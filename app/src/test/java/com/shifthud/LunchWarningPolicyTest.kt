package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LunchWarningPolicyTest {
    private val at = Instant.parse("2026-10-05T08:00:00Z")
    private val engine = ShiftEngine(Clock.fixed(at, ZoneOffset.UTC))
    private val session = WorkSession(id = 42, clockIn = at)
    private val settings = WarningSettings()
    private fun check(minutes: Long, previous: WarningLedger? = WarningLedger(42, settings, emptySet()),
                      config: WarningSettings = settings, s: WorkSession? = session, allowed: Boolean = true) =
        evaluateLunchWarning(s, config, previous, at.plusSeconds(minutes * 60), engine, allowed)
    @Test fun sixtyMinuteBoundary() { assertNull(check(299).offset); assertEquals(60, check(300).offset) }
    @Test fun thirtyMinuteBoundary() { assertEquals(30, check(330).offset) }
    @Test fun fifteenMinuteBoundary() { assertEquals(15, check(345).offset) }
    @Test fun disabledWarningDoesNotFire() {
        val config = WarningSettings(offsets = setOf(30, 15))
        assertNull(check(300, WarningLedger(42, config, emptySet()), config).offset)
    }
    @Test fun deliveredBoundaryDoesNotRepeatEachMinute() {
        val first = check(330).afterSuccessfulPost()
        assertEquals(30, first.offset)
        assertNull(check(331, first.ledger).offset)
        assertNull(check(344, first.ledger).offset)
        assertEquals(15, check(345, first.ledger).offset)
    }
    @Test fun lunchStartSuppressesAndCancelsWarnings() {
        val result = check(345, s = session.copy(lunchStart = at.plusSeconds(300 * 60), state = ShiftState.ON_LUNCH))
        assertNull(result.offset); assertTrue(result.cancel)
    }
    @Test fun completedLunchSuppressesWarningsEvenWithMoreActiveWork() {
        val result = check(700, s = session.copy(lunchStart = at.plusSeconds(300 * 60), lunchEnd = at.plusSeconds(330 * 60)))
        assertNull(result.offset); assertTrue(result.cancel)
    }
    @Test fun clockOutCancelsWarningLifecycle() {
        val result = check(350, s = session.copy(clockOut = at.plusSeconds(350 * 60), state = ShiftState.COMPLETE))
        assertNull(result.offset); assertNull(result.ledger); assertTrue(result.cancel)
    }
    @Test fun absentSessionCancels() { assertTrue(check(350, s = null).cancel) }
    @Test fun delayedExecutionSelectsOnlyLatestCrossedBoundary() {
        val result = check(340)
        assertEquals(30, result.offset)
        assertEquals(setOf(60), result.ledger!!.consumed) // Candidate 30 is not delivered yet.
        assertEquals("Lunch due in 20 minutes", warningTitle(Duration.ofMinutes(20)))
    }
    @Test fun executionAfterThresholdDoesNotEmitObsoleteReminder() {
        val result = check(365)
        assertNull(result.offset); assertTrue(result.cancel)
        assertEquals(settings.offsets, result.ledger!!.consumed)
    }
    @Test fun recoveredDeliveryHistoryPreventsDuplicates() {
        val saved = check(330).afterSuccessfulPost().ledger!!
        val reconstructed = WarningLedger(saved.sessionId, saved.settings.copy(), saved.consumed.toSet())
        assertNull(check(335, reconstructed).offset)
    }
    @Test fun firstObservationOfOldShiftSkipsHistoricalWarnings() { assertNull(check(340, null).offset) }
    @Test fun thresholdChangeBaselinesPastButRetainsFutureBoundary() {
        val changed = check(270, config = WarningSettings(300))
        assertNull(changed.offset); assertTrue(changed.cancel)
        assertEquals(setOf(60, 30), changed.ledger!!.consumed)
        assertEquals(15, check(285, changed.ledger, WarningSettings(300)).offset)
    }
    @Test fun newlyEnabledHistoricalWarningIsNotReplayed() {
        val old = WarningLedger(42, WarningSettings(offsets = emptySet()), emptySet())
        val result = check(340, old)
        assertNull(result.offset)
        assertEquals(15, check(345, result.ledger).offset)
    }
    @Test fun changingThresholdBackCannotReplayConsumedOffset() {
        val delivered = check(300).afterSuccessfulPost().ledger!!
        val changed = check(300, delivered, WarningSettings(420))
        assertNull(check(360, changed.ledger, WarningSettings(420)).offset)
    }
    @Test fun deniedPermissionKeepsCurrentBoundaryRetryableWithoutChangingShift() {
        val before = session.copy()
        val denied = check(330, allowed = false)
        assertNull(denied.offset); assertTrue(denied.cancel)
        assertEquals(30, check(331, denied.ledger, allowed = true).offset)
        assertEquals(before, session)
    }
    @Test fun noMetadataChangesBetweenBoundaries() {
        val ledger = check(300).afterSuccessfulPost().ledger!!
        assertEquals(ledger, check(310, ledger).ledger)
    }
    @Test fun shortThresholdSkipsImpossibleOffsetsAndSupportsTestWarnings() {
        val config = WarningSettings(10, setOf(60, 30, 15, 5, 1))
        val baseline = check(0, null, config)
        assertEquals(5, check(5, baseline.ledger, config).offset)
        assertEquals(1, check(9, check(5, baseline.ledger, config).ledger, config).offset)
    }
    @Test fun newSessionDoesNotInheritOldSessionHistory() {
        val result = check(0, WarningLedger(41, settings, settings.offsets))
        assertEquals(emptySet<Int>(), result.ledger!!.consumed)
        assertEquals(60, check(300, result.ledger).offset)
    }
}
