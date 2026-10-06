package com.shifthud

import android.app.Application
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WarningPostingOrderTest {
    private val at = Instant.parse("2026-10-05T08:00:00Z")
    private val engine = ShiftEngine(Clock.fixed(at, ZoneOffset.UTC))
    private suspend fun setup(id: Long): Pair<ShiftPreferences, WorkSession> {
        val prefs = ShiftPreferences(RuntimeEnvironment.getApplication())
        prefs.setLunchThreshold(10)
        prefs.setWarningEnabled(5, true)
        prefs.setWarningEnabled(1, true)
        val session = WorkSession(id = id, clockIn = at)
        prefs.deliverWarning(session, at, engine, true) { fail("No boundary yet"); false }
        return prefs to session
    }
    @Test fun successfulPostPrecedesDurableDedupAndSurvivesRecovery() = runBlocking {
        val (prefs, session) = setup(101)
        var posts = 0
        val result = prefs.deliverWarning(session, at.plusSeconds(301), engine, true) { candidate ->
            assertFalse(5 in candidate.ledger!!.consumed)
            assertFalse(candidate.posted)
            posts++
            true
        }
        assertTrue(result.posted)
        assertTrue(5 in result.ledger!!.consumed)
        ShiftPreferences(RuntimeEnvironment.getApplication()).deliverWarning(session, at.plusSeconds(302), engine, true) { posts++; true }
        assertEquals(1, posts)
    }
    @Test fun thrownPostingFailureLeavesCurrentWarningRetryable() = runBlocking {
        val (prefs, session) = setup(102)
        assertTrue(runCatching { prefs.deliverWarning(session, at.plusSeconds(301), engine, true) { throw SecurityException("Simulated notify failure") } }.isFailure)
        val retry = prefs.deliverWarning(session, at.plusSeconds(302), engine, true) { candidate ->
            assertEquals(5, candidate.offset)
            assertFalse(5 in candidate.ledger!!.consumed)
            true
        }
        assertTrue(retry.posted)
    }
    @Test fun suppressedPostDoesNotRecordSuccess() = runBlocking {
        val (prefs, session) = setup(103)
        val failed = prefs.deliverWarning(session, at.plusSeconds(301), engine, true) { false }
        assertFalse(failed.posted)
        assertFalse(5 in failed.ledger!!.consumed)
        assertEquals(5, prefs.deliverWarning(session, at.plusSeconds(302), engine, true) { true }.offset)
    }
    @Test fun blockedPermissionDoesNotConsumeCurrentlyRelevantWarning() = runBlocking {
        val (prefs, session) = setup(104)
        val denied = prefs.deliverWarning(session, at.plusSeconds(301), engine, false) { fail("Blocked post invoked"); false }
        assertFalse(denied.posted)
        assertFalse(5 in denied.ledger!!.consumed)
        assertEquals(5, prefs.deliverWarning(session, at.plusSeconds(302), engine, true) { true }.offset)
    }
    @Test fun failedFiveMinutePostDoesNotBurstWhenOneMinuteBoundaryArrives() = runBlocking {
        val (prefs, session) = setup(105)
        prefs.deliverWarning(session, at.plusSeconds(301), engine, true) { false }
        val attempts = mutableListOf<Int>()
        val result = prefs.deliverWarning(session, at.plusSeconds(541), engine, true) { attempts += it.offset!!; true }
        assertEquals(listOf(1), attempts)
        assertTrue(result.ledger!!.consumed.containsAll(listOf(5, 1)))
    }
    @Test fun concurrentEvaluationsPostTheBoundaryOnlyOnce() = runBlocking {
        val (prefs, session) = setup(106)
        val posts = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            (1..3).map { async(Dispatchers.IO) {
                prefs.deliverWarning(session, at.plusSeconds(301), engine, true) { posts.incrementAndGet(); true }
            } }.awaitAll()
        }
        assertEquals(1, posts.get())
    }
    @Test fun rapidOffsetSavesRetainBothTestOffsetsInSharedPipeline() = runBlocking {
        val prefs = ShiftPreferences(RuntimeEnvironment.getApplication())
        prefs.setWarningEnabled(5, false); prefs.setWarningEnabled(1, false)
        coroutineScope {
            val five = async { prefs.setWarningEnabled(5, true) }
            val one = async { prefs.setWarningEnabled(1, true) }
            five.await(); one.await()
        }
        assertTrue(prefs.warningSettings.first().offsets.containsAll(listOf(5, 1)))
    }
}
