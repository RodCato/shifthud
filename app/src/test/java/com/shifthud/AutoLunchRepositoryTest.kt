package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.*
import com.shifthud.data.repository.*
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.*
import com.shifthud.notification.*
import com.shifthud.service.autoLunchRefreshDelayMillis
import com.shifthud.ui.completedLunch
import com.shifthud.widget.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AutoLunchRepositoryTest {
    private val base = Instant.parse("2026-10-05T04:00:00Z")
    private var now = base
    private val clock = object: Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
    private val engine = ShiftEngine(clock)
    private lateinit var db: ShiftDatabase
    private lateinit var repo: ShiftRepository
    private var settings = AutoLunchSettings()
    private var refreshes = 0
    private fun repository() = ShiftRepository(db,engine,clock,onChanged={ refreshes++ },autoLunchSettings={settings})
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build();repo=repository() }
    @After fun close() { db.close() }
    private suspend fun lunch(): WorkSession {
        repo.clockIn(); now=base.plusSeconds(3600)
        val s=repo.snapshot().session!!;repo.transition(s.id,s.state,engine::startLunch)
        return repo.snapshot().session!!
    }
    @Test fun defaultAndSupportedDurationsCaptured() = runBlocking {
        assertEquals(60,settings.minutes); assertTrue(settings.enabled)
        for (n in AUTO_LUNCH_CHOICES) {
            settings=AutoLunchSettings(true,n)
            repo.clockIn();val working=repo.snapshot().session!!
            repo.transition(working.id,working.state,engine::startLunch)
            val captured=repo.snapshot().session!!
            assertEquals(n,captured.autoLunchMinutes);assertEquals(now.plusSeconds(n*60L),captured.autoLunchEndTarget)
            now=captured.autoLunchEndTarget!!;repo.reconcileAutoLunch()
            val ended=repo.snapshot().session!!;repo.transition(ended.id,ended.state,engine::clockOut)
            now=now.plusSeconds(60)
        }
    }
    @Test fun defaultChangeDoesNotMoveActiveTarget() = runBlocking {
        val s=lunch();settings=AutoLunchSettings(false,90)
        assertEquals(s.autoLunchEndTarget,repo.snapshot().session!!.autoLunchEndTarget)
        now=s.autoLunchEndTarget!!;assertTrue(repo.reconcileAutoLunch())
    }
    @Test fun disabledLeavesLunchActive() = runBlocking {
        settings=AutoLunchSettings(false,60);lunch();now=now.plusSeconds(7200)
        assertFalse(repo.reconcileAutoLunch());assertEquals(ShiftState.ON_LUNCH,repo.snapshot().session!!.state)
    }
    @Test fun manualEndBeforeTargetWins() = runBlocking {
        val s=lunch();now=now.plusSeconds(1800);repo.transition(s.id,s.state,engine::endLunch)
        val manual=repo.snapshot().session!!;now=now.plusSeconds(7200)
        assertFalse(repo.reconcileAutoLunch());assertEquals(manual,repo.snapshot().session);assertFalse(manual.lunchEndAutomatic)
    }
    @Test fun exactTargetAutomaticallyPersistsWorkingAndProvenance() = runBlocking {
        val s=lunch();now=s.autoLunchEndTarget!!
        assertTrue(repo.reconcileAutoLunch());val ended=repo.snapshot().session!!
        assertEquals(now,ended.lunchEnd);assertEquals(ShiftState.WORKING,ended.state);assertTrue(ended.lunchEndAutomatic)
        assertFalse(repo.reconcileAutoLunch())
    }
    @Test fun delayedRecoveryPersistsTargetNotWakeTime() = runBlocking {
        val s=lunch();now=s.autoLunchEndTarget!!.plusSeconds(300)
        repo=repository();assertTrue(repo.reconcileAutoLunch())
        val ended=repo.snapshot().session!!
        assertEquals(s.autoLunchEndTarget,ended.lunchEnd)
        assertEquals(65,engine.durations(ended,360).paid.toMinutes())
        assertEquals(60,engine.durations(ended,360).lunch.toMinutes())
    }
    @Test fun correctedAutoEndClearsProvenanceAndRecalculates() = runBlocking {
        val s=lunch();now=s.autoLunchEndTarget!!.plusSeconds(300);repo.reconcileAutoLunch()
        val ended=repo.snapshot().session!!
        repo.correct(ended,TimeEvent.LUNCH_END,ended.lunchStart!!.plusSeconds(2700))
        val edited=repo.snapshot().session!!
        assertFalse(edited.lunchEndAutomatic);assertEquals(80,engine.durations(edited,360).paid.toMinutes())
        assertTrue(refreshes>=3)
    }
    @Test fun invalidAndStaleCorrectionLeaveOriginalIntact() = runBlocking {
        val s=lunch()
        assertTrue(runCatching {repo.correct(s,TimeEvent.CLOCK_IN,now.plusSeconds(1))}.isFailure)
        assertEquals(s,repo.snapshot().session)
        repo.correct(s,TimeEvent.CLOCK_IN,base.minusSeconds(60))
        val corrected=repo.snapshot().session
        assertTrue(runCatching {repo.correct(s,TimeEvent.CLOCK_IN,base.minusSeconds(120))}.isFailure)
        assertEquals(corrected,repo.snapshot().session)
    }
    @Test fun completedCorrectionPersistsAndRemainsLatestByIdentity() = runBlocking {
        repo.clockIn();val first=repo.snapshot().session!!;now=now.plusSeconds(600);repo.transition(first.id,first.state,engine::clockOut)
        now=now.plusSeconds(600);repo.clockIn();val next=repo.snapshot().session!!
        now=now.plusSeconds(600);repo.transition(next.id,next.state,engine::clockOut)
        repo.correct(repo.snapshot().session!!,TimeEvent.CLOCK_IN,base.minusSeconds(600))
        assertEquals(next.id,repo.snapshot().session!!.id)
        assertEquals(40,engine.durations(repo.snapshot().session!!,360).paid.toMinutes())
    }
    @Test fun correctingActiveLunchStartMovesCapturedTarget() = runBlocking {
        val s=lunch();repo.correct(s,TimeEvent.LUNCH_START,now.minusSeconds(120))
        assertEquals(s.autoLunchEndTarget!!.minusSeconds(120),repo.snapshot().session!!.autoLunchEndTarget)
    }
    @Test fun presentationAndWarningsAgreeAfterAutoEnd() = runBlocking {
        val s=lunch();now=s.autoLunchEndTarget!!;repo.reconcileAutoLunch();val ended=repo.snapshot().session!!
        val widget=WidgetStateFactory(engine).create(emptyList(),ended,60,now,ZoneOffset.UTC,Locale.US)
        assertTrue(widget.detail.contains("Auto"));assertFalse(widget.detail.contains("Lunch due"))
        val notification=ShiftNotificationStateFactory(engine).create(repo.snapshot(),60,now,ZoneOffset.UTC,Locale.US,false)!!
        assertTrue(notification.secondary.any {it.contains("Auto")});assertNull(notification.action)
        assertNull(evaluateLunchWarning(ended,WarningSettings(60),null,now,engine,true).offset)
        assertNull(evaluateLunchAttention(ended,LunchAttention(ended.id),60,now,engine).state)
        assertTrue(completedLunch(ended,ZoneOffset.UTC,Locale.US,false)!!.compactDetail.contains("Auto"))
    }
    @Test fun serviceWakeConsidersTargetWithoutPolling() = runBlocking {
        val s=lunch();val target=s.autoLunchEndTarget!!
        assertEquals(1234,autoLunchRefreshDelayMillis(s,target.minusMillis(1234),60000))
        assertEquals(500,autoLunchRefreshDelayMillis(s,target.minusMillis(1234),500))
        assertEquals(60000,autoLunchRefreshDelayMillis(s,target.plusSeconds(5),60000))
    }
    @Test fun clockOutAfterAutoEndRemainsComplete() = runBlocking {
        val s=lunch();now=s.autoLunchEndTarget!!;repo.reconcileAutoLunch();val ended=repo.snapshot().session!!
        repo.transition(ended.id,ended.state,engine::clockOut);assertFalse(repo.reconcileAutoLunch())
        assertEquals(ShiftState.COMPLETE,repo.snapshot().session!!.state)
    }
}
