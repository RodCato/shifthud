package com.shifthud

import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WarningDeliveryRegressionTest {
    private val at = Instant.parse("2026-10-05T08:00:00Z")
    private val engine = ShiftEngine(Clock.fixed(at, ZoneOffset.UTC))
    private val session = WorkSession(id = 99, clockIn = at)
    private val config = WarningSettings(10, setOf(60, 30, 15, 5, 1))
    private fun evaluate(seconds: Long, ledger: WarningLedger? = WarningLedger(99, config, emptySet()), settings: WarningSettings = config) =
        evaluateLunchWarning(session, settings, ledger, at.plusSeconds(seconds), engine, true)
    @Test fun tenMinuteThresholdFiveMinuteCrossing() { assertEquals(5, evaluate(300).offset) }
    @Test fun tenMinuteThresholdOneMinuteCrossing() { assertEquals(1, evaluate(540).offset) }
    @Test fun impossibleOffsetsDoNotInterfereWithFiveAndOne() {
        assertEquals(listOf(5, 1), config.validOffsets)
        assertEquals(5, evaluate(301).offset)
        assertEquals(1, evaluate(541).offset)
    }
    @Test fun fourFiftyNineToFiveOhOneDoesNotRequireEquality() {
        val before = evaluate(299)
        assertNull(before.offset)
        assertEquals(5, evaluate(301, before.ledger).offset)
    }
    @Test fun eightFiftyNineToNineOhOneSelectsCurrentOneMinuteWarning() {
        val fivePosted = evaluate(301).afterSuccessfulPost().ledger
        val before = evaluate(539, fivePosted)
        assertNull(before.offset)
        assertEquals(1, evaluate(541, before.ledger).offset)
    }
    @Test fun delayedCheckRetiresOldBoundaryAndSelectsOneCurrentWarning() {
        val decision = evaluate(550)
        assertEquals(1, decision.offset)
        assertTrue(5 in decision.ledger!!.consumed)
        assertFalse(1 in decision.ledger!!.consumed) // Not yet posted.
        val posted = decision.afterSuccessfulPost()
        assertNull(evaluate(551, posted.ledger).offset)
    }
    @Test fun reachingThresholdCannotGeneratePreLunchWarning() {
        assertNull(evaluate(600).offset)
        assertNull(evaluate(601).offset)
        assertEquals("threshold_reached", evaluate(601).reason)
    }
    @Test fun shortThresholdWithOnlyDefaultsExplainsMissingCandidate() {
        val invalid = WarningSettings(10)
        val baseline = evaluate(0, null, invalid)
        val later = evaluate(301, baseline.ledger, invalid)
        assertNull(later.offset)
        assertEquals("no_valid_offsets", later.reason)
        assertTrue(invalid.boundarySummary().startsWith("No enabled warnings"))
        assertEquals("Saved reminder boundaries: 5m worked (5m before), 9m worked (1m before)", config.boundarySummary())
    }
}
