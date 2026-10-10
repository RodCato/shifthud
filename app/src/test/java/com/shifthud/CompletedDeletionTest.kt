package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.calendar.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.ui.display
import com.shifthud.notification.notificationDuration
import com.shifthud.widget.widgetDuration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CompletedDeletionTest {
    private lateinit var db:ShiftDatabase
    private lateinit var repo:ShiftRepository
    private val now=Instant.parse("2026-10-06T18:00:00Z")
    private val zone=ZoneOffset.UTC
    private val engine=ShiftEngine(Clock.fixed(now,zone))
    private val estimator=PayEstimator(engine)
    private val deleted=mutableListOf<Long>()
    private var redraws=0
    @Before fun setup(){
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build()
        repo=ShiftRepository(db,engine,Clock.fixed(now,zone),onChanged={error("Deletion must not run active lifecycle refresh")},
            onHistoricalChanged={redraws++},onSessionDeleted={id->assertNull(db.shifts().session(id));deleted+=id})
    }
    @After fun close(){db.close()}
    private suspend fun insert(hour:Int=4,manual:Boolean=false):WorkSession {
        val start=Instant.parse("2026-10-04T00:00:00Z").plusSeconds(hour*3600L)
        val s=WorkSession(clockIn=start,clockOut=start.plusSeconds(3600),state=ShiftState.COMPLETE,manuallyEntered=manual)
        return s.copy(id=db.shifts().insert(s.entity()))
    }
    private suspend fun day()=workCalendar(YearMonth.of(2026,10),db.shifts().scheduleSnapshot().map{it.model()},repo.sessions.first(),DEFAULT_PAY_RATES,now,zone,estimator).single{it.date==LocalDate.of(2026,10,4)}
    @Test fun normallyRecordedCompleteDeletesAndRunsScopedCleanupAfterCommit()=runBlocking {val s=insert();repo.deleteHistorical(s);assertNull(db.shifts().session(s.id));assertEquals(listOf(s.id),deleted);assertEquals(1,redraws)}
    @Test fun manuallyAddedCompleteStillDeletes()=runBlocking {val s=insert(manual=true);repo.deleteHistorical(s);assertTrue(repo.sessions.first().isEmpty())}
    @Test fun workingCannotBeDeletedEvenDirectlyThroughDao()=runBlocking {
        val s=WorkSession(clockIn=now);val id=db.shifts().insert(s.entity());assertTrue(runCatching{repo.deleteHistorical(s.copy(id=id))}.isFailure)
        assertEquals(0,db.shifts().deleteHistorical(id));assertNotNull(db.shifts().session(id));assertTrue(deleted.isEmpty());assertEquals(0,redraws)
    }
    @Test fun lunchCannotBeDeleted()=runBlocking {
        val s=WorkSession(clockIn=now.minusSeconds(3600),lunchStart=now,state=ShiftState.ON_LUNCH)
        val id=db.shifts().insert(s.entity());assertTrue(runCatching{repo.deleteHistorical(s.copy(id=id))}.isFailure);assertEquals(0,db.shifts().deleteHistorical(id));assertNotNull(db.shifts().session(id))
    }
    @Test fun exactIdDeletionPreservesOtherSameDayRecordsAndDot()=runBlocking {
        val first=insert();val second=insert(9);val third=insert(15)
        repo.deleteHistorical(second)
        assertEquals(listOf(first,third),repo.sessions.first());assertTrue(day().hasCompletedSession);assertEquals(listOf(second.id),deleted)
        assertEquals(Duration.ofHours(2),day().paidDuration)
    }
    @Test fun lastDeletionClearsWorkedDotAndTotals()=runBlocking {
        val s=insert();repo.deleteHistorical(s);assertEquals("–",day().indicator)
        assertEquals(PayEstimate(Duration.ZERO,0),estimator.week(repo.sessions.first(),DEFAULT_PAY_RATES,now,zone))
    }
    @Test fun linkedScheduleSurvivesAndBecomesScheduledOnly()=runBlocking {
        val shift=ScheduledShift(date=LocalDate.of(2026,10,4),scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0))
        db.shifts().save(shift.entity());val saved=db.shifts().scheduleSnapshot().single()
        val s=insert().copy(scheduledShiftId=saved.id);db.shifts().update(s.entity());repo.deleteHistorical(s)
        assertEquals(listOf(saved),db.shifts().scheduleSnapshot());assertEquals("○",day().indicator);assertFalse(day().hasCompletedSession)
    }
    @Test fun exactPaidAndGrossContributionRemoved()=runBlocking {
        val a=insert();val b=insert(15)
        val before=estimator.week(repo.sessions.first(),DEFAULT_PAY_RATES,now,zone)
        repo.deleteHistorical(a)
        val after=estimator.week(repo.sessions.first(),DEFAULT_PAY_RATES,now,zone)
        assertEquals(before.paid.minusHours(1),after.paid);assertEquals(before.grossCents-1600,after.grossCents);assertEquals(listOf(b),repo.sessions.first())
    }
    @Test fun olderDeletionPreservesTodaysActiveState()=runBlocking {
        val old=insert();val active=WorkSession(clockIn=now.minusSeconds(3600));val id=db.shifts().insert(active.entity())
        repo.deleteHistorical(old);assertEquals(active.copy(id=id),repo.snapshot().session);assertEquals(active.copy(id=id),repo.latestSession.first())
    }
    @Test fun olderDeletionPreservesTodaysCompletedState()=runBlocking {
        val old=insert();val today=WorkSession(clockIn=now.minusSeconds(3600),clockOut=now,state=ShiftState.COMPLETE);val id=db.shifts().insert(today.entity())
        repo.deleteHistorical(old);assertEquals(today.copy(id=id),repo.snapshot().session)
    }
    @Test fun concurrentDeleteDoesNotTouchOtherIdsOrCleanTwice()=runBlocking {
        val a=insert();val b=insert(15)
        val results=coroutineScope{(1..2).map{async(Dispatchers.IO){runCatching{repo.deleteHistorical(a)}.isSuccess}}.awaitAll()}
        assertEquals(1,results.count{it});assertEquals(listOf(a.id),deleted);assertEquals(listOf(b),repo.sessions.first())
    }
    @Test fun minuteFormattingSuppressesAllSecondPrecision(){
        listOf(Duration.ofMillis(22754013) to "6h 19m",Duration.ofMillis(208861) to "3m",Duration.ofMillis(59999) to "0m",Duration.ofMinutes(115) to "1h 55m").forEach{(d,label)->
            assertEquals(label,recordDuration(d));assertEquals(label,d.display());assertEquals(label,d.widgetDuration());assertEquals(label,d.notificationDuration())
        }
    }
    @Test fun minuteDisplayDoesNotChangePreciseGross(){
        val duration=Duration.ofMillis(208861)
        assertEquals("3m",recordDuration(duration));assertEquals(93L,grossCents(duration,1600));assertEquals(80L,grossCents(Duration.ofMinutes(3),1600))
    }
}
