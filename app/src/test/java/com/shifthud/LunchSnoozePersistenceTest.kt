package com.shifthud

import android.app.Application
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LunchSnoozePersistenceTest {
    private val start = Instant.parse("2026-10-05T08:00:00Z")
    private val engine = ShiftEngine(Clock.fixed(start, ZoneOffset.UTC))
    private fun prefs() = ShiftPreferences(RuntimeEnvironment.getApplication())
    private suspend fun setup(id: Long): Pair<ShiftPreferences, WorkSession> {
        val p = prefs(); p.setLunchThreshold(5); p.setSnoozeMinutes(10)
        val s = WorkSession(id = id, clockIn = start)
        p.deliverAttention(s, start.plusSeconds(300), engine, true) { _, _ -> true }
        return p to s
    }
    @org.junit.Before @org.junit.After fun resetPreferences() = runBlocking {
        val p = prefs(); p.setLunchThreshold(360); p.setSnoozeMinutes(10)
        p.deliverAttention(null, start, engine, true) { _, _ -> false }
        Unit
    }
    @Test fun defaultIsTenAndInvalidDurationsAreRejected() = runBlocking {
        val p = prefs(); assertEquals(10, p.snoozeMinutes.first())
        listOf(0, -1, 1, 30).forEach { assertTrue(runCatching { p.setSnoozeMinutes(it) }.isFailure) }
        SNOOZE_CHOICES.forEach { p.setSnoozeMinutes(it); assertEquals(it, p.snoozeMinutes.first()) }
    }
    @Test fun failedAndBlockedPostNeverAcknowledged() = runBlocking {
        val p = prefs(); p.setLunchThreshold(5); val s = WorkSession(id = 201, clockIn = start)
        assertFalse(p.deliverAttention(s, start.plusSeconds(301), engine, false) { _, _ -> fail("Blocked"); true }.state!!.posted)
        assertFalse(p.deliverAttention(s, start.plusSeconds(302), engine, true) { _, _ -> false }.state!!.posted)
        assertTrue(runCatching { p.deliverAttention(s, start.plusSeconds(303), engine, true) { _, _ -> throw IllegalStateException() } }.isFailure)
        val posted = p.deliverAttention(s, start.plusSeconds(304), engine, true) { event, _ -> assertFalse(event.posted); true }
        assertTrue(posted.state!!.posted)
        prefs().deliverAttention(s, start.plusSeconds(305), engine, true) { _, _ -> fail("Duplicate"); false }; Unit
    }
    @Test fun snoozeSurvivesWrapperRecoveryAndDoesNotChangeThresholdOrSession() = runBlocking {
        val (p, s) = setup(202)
        assertTrue(p.snooze(s.id, "202:attention:0", { s }, start.plusMillis(304123), engine))
        val recovered = prefs().deliverAttention(s, start.plusSeconds(400), engine, true) { _, _ -> fail("Too early"); false }
        assertEquals(904123L, recovered.state!!.targetActiveMillis)
        assertEquals(5, p.lunchThresholdMinutes.first())
        assertEquals(WorkSession(id = 202, clockIn = start), s)
    }
    @Test fun preferenceChangeLeavesTargetButAffectsNextSnooze() = runBlocking {
        val (p, s) = setup(203)
        p.snooze(s.id, "203:attention:0", { s }, start.plusSeconds(304), engine)
        p.setSnoozeMinutes(5)
        val waiting = p.deliverAttention(s, start.plusSeconds(400), engine, true) { _, _ -> false }
        assertEquals(904000L, waiting.state!!.targetActiveMillis)
        p.deliverAttention(s, start.plusSeconds(905), engine, true) { _, minutes -> assertEquals(5, minutes); true }
        assertTrue(p.snooze(s.id, "203:attention:1", { s }, start.plusSeconds(910), engine))
        val next = p.deliverAttention(s, start.plusSeconds(911), engine, true) { _, _ -> false }
        assertEquals(1210000L, next.state!!.targetActiveMillis)
    }
    @Test fun repeatedAndStaleActionsCannotChangeCurrentTarget() = runBlocking {
        val (p, s) = setup(204)
        assertTrue(p.snooze(s.id, "204:attention:0", { s }, start.plusSeconds(304), engine))
        assertFalse(p.snooze(s.id, "204:attention:0", { s }, start.plusSeconds(310), engine))
        val next = s.copy(id = 205)
        p.deliverAttention(next, start.plusSeconds(300), engine, true) { _, _ -> true }
        assertFalse(p.snooze(s.id, "204:attention:0", { next }, start.plusSeconds(320), engine))
        val current = p.deliverAttention(next, start.plusSeconds(330), engine, true) { _, _ -> fail("Duplicate"); false }
        assertEquals(LunchAttention(205, posted = true), current.state)
    }
    @Test fun concurrentSnoozeTapsOnlyAcceptOne() = runBlocking {
        val (p, s) = setup(206)
        val accepted = coroutineScope { (1..3).map { async { p.snooze(s.id, "206:attention:0", { s }, start.plusSeconds(304), engine) } }.awaitAll() }
        assertEquals(1, accepted.count { it })
    }
    @Test fun obsoleteRecoveryClearsMetadataDurably() = runBlocking {
        val (p, s) = setup(207); p.snooze(s.id, "207:attention:0", { s }, start.plusSeconds(304), engine)
        val lunch = s.copy(lunchStart = start.plusSeconds(305), state = ShiftState.ON_LUNCH)
        assertNull(prefs().deliverAttention(lunch, start.plusSeconds(1000), engine, true) { _, _ -> fail("On lunch"); false }.state)
        assertFalse(prefs().snooze(s.id, "207:attention:1", { lunch }, start.plusSeconds(1001), engine))
    }
    @Test fun delayedSnoozeRecoveryPostsOnceAndPersistsAcknowledgement() = runBlocking {
        val (p, s) = setup(208); p.snooze(s.id, "208:attention:0", { s }, start.plusSeconds(304), engine)
        var calls = 0
        repeat(3) { prefs().deliverAttention(s, start.plusSeconds(1000L + it), engine, true) { event, _ ->
            assertEquals(1L, event.generation); calls++; true
        } }
        assertEquals(1, calls)
    }
}
