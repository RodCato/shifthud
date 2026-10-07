package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.*
import com.shifthud.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class HistoricalRepositoryTest {
    private lateinit var db: ShiftDatabase
    private lateinit var repo: ShiftRepository
    private val now=Instant.parse("2026-10-06T18:00:00Z")
    private val zone=ZoneOffset.UTC
    private val engine=ShiftEngine(Clock.fixed(now,zone))
    private val pay=PayEstimator(engine)
    private var liveRefreshes=0
    private var historicalRefreshes=0
    private fun input(day:Int=5,start:String="04:00",end:String="13:00")=HistoricalShiftInput(LocalDate.of(2026,10,day),LocalTime.parse(start),LocalTime.parse(end))
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build()
        repo=ShiftRepository(db,engine,Clock.fixed(now,zone),onChanged={liveRefreshes++},onHistoricalChanged={historicalRefreshes++})
    }
    @After fun close(){db.close()}
    private suspend fun read(id:Long)=repo.sessions.first().single{it.id==id}
    @Test fun insertsCompleteTransactionallyAndUsesOnlyHistoricalRedraw()=runBlocking {
        val id=repo.addHistorical(input(),zone)
        val s=read(id)
        assertTrue(s.manuallyEntered);assertEquals(ShiftState.COMPLETE,s.state)
        assertNull(db.shifts().active());assertEquals(0,liveRefreshes);assertEquals(1,historicalRefreshes)
        assertFalse(repo.reconcileAutoLunch(now));assertNull(s.autoLunchEndTarget)
    }
    @Test fun exactDuplicateRejectedWithExistingRecord()=runBlocking {
        val id=repo.addHistorical(input(),zone)
        val error=runCatching {repo.addHistorical(input(),zone)}.exceptionOrNull() as SessionOverlapException
        assertEquals(id,error.existing.id);assertEquals(1,repo.sessions.first().size);assertEquals(1,historicalRefreshes)
    }
    @Test fun partialOverlapRejected()=runBlocking {
        repo.addHistorical(input(),zone)
        assertTrue(runCatching {repo.addHistorical(input(start="12:00",end="17:00"),zone)}.exceptionOrNull() is SessionOverlapException)
    }
    @Test fun adjacentSameDaySessionsAllowed()=runBlocking {
        repo.addHistorical(input(),zone);repo.addHistorical(input(start="13:00",end="17:00"),zone)
        assertEquals(2,repo.sessions.first().size)
    }
    @Test fun concurrentDuplicateSavesInsertOnlyOnce()=runBlocking {
        val results=coroutineScope{(1..2).map{async(Dispatchers.IO){runCatching{repo.addHistorical(input(),zone)}.isSuccess}}.awaitAll()}
        assertEquals(1,results.count{it});assertEquals(1,repo.sessions.first().size)
    }
    @Test fun liveActiveSessionWinsEvenWhenOldSessionInsertedLater()=runBlocking {
        repo.clockIn();val active=repo.latestSession.first()!!
        repo.addHistorical(input(),zone)
        assertEquals(active,repo.latestSession.first());assertEquals(active,repo.snapshot().session)
        val widget=WidgetStateFactory(engine).create(emptyList(),repo.snapshot().session,360,now,zone,Locale.US)
        assertEquals(WidgetStatus.WORKING,widget.status)
        assertEquals(1,liveRefreshes)
    }
    @Test fun historicalInsertDoesNotReconcileAnOverdueActiveLunch()=runBlocking {
        val active=WorkSession(clockIn=Instant.parse("2026-10-06T04:00:00Z"),
            lunchStart=Instant.parse("2026-10-06T09:00:00Z"),state=ShiftState.ON_LUNCH,autoLunchMinutes=60)
        val id=db.shifts().insert(active.entity())
        repo.addHistorical(input(),zone)
        assertEquals(active.copy(id=id),repo.snapshot().session)
        assertEquals(0,liveRefreshes);assertEquals(1,historicalRefreshes)
    }
    @Test fun todaysCompleteSessionWinsByPunchesInsteadOfId()=runBlocking {
        val todayId=repo.addHistorical(input(day=6),zone)
        repo.addHistorical(input(day=3),zone)
        assertEquals(todayId,repo.latestSession.first()!!.id);assertEquals(todayId,repo.snapshot().session!!.id)
        val widget=WidgetStateFactory(engine).create(emptyList(),repo.snapshot().session,360,now,zone,Locale.US)
        assertEquals(WidgetStatus.COMPLETE,widget.status)
    }
    @Test fun onlyHistoricalSessionLeavesTodaysScheduleStateAlone()=runBlocking {
        repo.save(ScheduledShift(date=LocalDate.of(2026,10,6),scheduledStart=LocalTime.of(19,0),scheduledEnd=LocalTime.of(23,0)))
        repo.addHistorical(input(day=3),zone)
        val snapshot=repo.snapshot()
        val widget=WidgetStateFactory(engine).create(snapshot.schedule,snapshot.session,360,now,zone,Locale.US)
        assertEquals(WidgetStatus.TODAY,widget.status)
        assertNull(snapshot.session?.takeIf {it.state!=ShiftState.COMPLETE || it.clockOut!!.atZone(zone).toLocalDate()==now.atZone(zone).toLocalDate()})
    }
    @Test fun overlappingActiveSessionRejectedWithoutTransition()=runBlocking {
        val active=WorkSession(clockIn=Instant.parse("2026-10-05T10:00:00Z"))
        val id=db.shifts().insert(active.entity())
        assertTrue(runCatching{repo.addHistorical(input(),zone)}.exceptionOrNull() is SessionOverlapException)
        assertEquals(active.copy(id=id),repo.latestSession.first())
    }
    @Test fun weeklyFlowUpdatesAfterInsertionCorrectionAndDeletion()=runBlocking {
        val observed=mutableListOf<PayEstimate>()
        val job=launch(start=CoroutineStart.UNDISPATCHED){repo.sessions.map{pay.week(it,DEFAULT_PAY_RATES,now,zone)}.collect{observed+=it}}
        suspend fun awaitPaid(minutes:Long)=withTimeout(5000){while(observed.lastOrNull()?.paid!=Duration.ofMinutes(minutes))delay(10)}
        awaitPaid(0)
        val id=repo.addHistorical(input(),zone);awaitPaid(540)
        assertEquals(14400L,observed.last().grossCents)
        val before=read(id)
        repo.correct(before,TimeEvent.CLOCK_OUT,Instant.parse("2026-10-05T12:00:00Z"));awaitPaid(480)
        val after=read(id);assertTrue(after.manuallyEntered);assertEquals(1L,after.correctionRevision)
        assertEquals(12800L,observed.last().grossCents)
        repo.deleteHistorical(after);awaitPaid(0);assertEquals(0L,observed.last().grossCents)
        job.cancelAndJoin();assertEquals(0,liveRefreshes);assertEquals(3,historicalRefreshes)
    }
    @Test fun lunchInputPersistedWithHistoricalRateAndWeekGross()=runBlocking {
        val id=repo.addHistorical(input().copy(lunchStart=LocalTime.of(9,30),lunchEnd=LocalTime.of(10,24)),zone)
        val s=read(id);assertEquals(Instant.parse("2026-10-05T09:30:00Z"),s.lunchStart)
        val rates=listOf(PayRate(LocalDate.MIN,1500),PayRate(LocalDate.of(2026,10,6),2000))
        assertEquals(12150L,pay.week(repo.sessions.first(),rates,now,zone).grossCents)
    }
    @Test fun scheduleLinkedWithoutChangingPlannedTimes()=runBlocking {
        repo.save(ScheduledShift(date=input().date,scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0)))
        val before=repo.schedule.first().single()
        val s=read(repo.addHistorical(input(end="12:41"),zone))
        assertEquals(before.id,s.scheduledShiftId);assertEquals(before,repo.schedule.first().single())
    }
    @Test fun unscheduledHistoricalSessionValid()=runBlocking {assertNull(read(repo.addHistorical(input(),zone)).scheduledShiftId)}
    @Test fun liveRecordedCompleteCannotBeDeleted()=runBlocking {
        val original=input().session(zone,now).copy(manuallyEntered=false)
        val id=db.shifts().insert(original.entity())
        assertTrue(runCatching{repo.deleteHistorical(read(id))}.isFailure);assertEquals(1,repo.sessions.first().size)
    }
    @Test fun staleDeletionCannotEraseUpdatedPunches()=runBlocking {
        val s=read(repo.addHistorical(input(),zone))
        repo.correct(s,TimeEvent.CLOCK_OUT,s.clockOut!!.minusSeconds(60))
        assertTrue(runCatching{repo.deleteHistorical(s)}.isFailure);assertEquals(1,repo.sessions.first().size)
    }
    @Test fun historicalCorrectionCannotOverlapAnotherRecord()=runBlocking {
        val s=read(repo.addHistorical(input(),zone));repo.addHistorical(input(start="14:00",end="17:00"),zone)
        assertTrue(runCatching{repo.correct(s,TimeEvent.CLOCK_OUT,Instant.parse("2026-10-05T15:00:00Z"))}.exceptionOrNull() is SessionOverlapException)
        assertEquals(s,read(s.id))
    }
    @Test fun persistedSaturdayRecordCountsWithoutReinsertionOrTimestampChanges()=runBlocking {
        // Existing schema-5 row, as saved by the previous build; no historical-insert hook needed.
        val zone=ZoneId.of("America/Chicago")
        val original=HistoricalShiftInput(LocalDate.of(2026,10,3),LocalTime.of(4,0),LocalTime.of(13,0),LocalTime.of(9,47),LocalTime.of(10,41)).session(zone,now)
        val id=db.shifts().insert(original.entity())
        val before=repo.sessions.first()
        assertEquals(PayEstimate(Duration.ofMinutes(486),12960),pay.week(before,DEFAULT_PAY_RATES,now,zone))
        assertEquals(before,repo.sessions.first());assertEquals(original.copy(id=id),read(id))
        assertTrue(runCatching{repo.addHistorical(HistoricalShiftInput(LocalDate.of(2026,10,3),LocalTime.of(4,0),LocalTime.of(13,0),LocalTime.of(9,47),LocalTime.of(10,41)),zone)}.exceptionOrNull() is SessionOverlapException)
        assertEquals(1,repo.sessions.first().size);assertEquals(0,historicalRefreshes)
        assertEquals(5,db.openHelper.readableDatabase.version)
    }
    @Test fun rejectedChronologyDoesNotPersistOrRefresh()=runBlocking {
        assertTrue(runCatching{repo.addHistorical(input(end="04:00"),zone)}.isFailure)
        assertTrue(repo.sessions.first().isEmpty());assertEquals(0,historicalRefreshes)
    }
}
