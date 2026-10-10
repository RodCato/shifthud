package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.data.repository.*
import com.shifthud.domain.importing.*
import com.shifthud.domain.analytics.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.payroll.*
import com.shifthud.domain.usecase.*
import com.shifthud.domain.weekly.WeeklyTargetSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class BackfillRepositoryTest {
    private lateinit var db:ShiftDatabase
    private lateinit var repo:ShiftRepository
    private val zone=ZoneId.of("America/New_York")
    private val clock=Clock.fixed(Instant.parse("2026-10-10T18:00:00Z"),zone)
    private val dates=PUBLIX_BACKFILL.map{it.date}.toSet()
    private var redraws=0
    private var otherEffects=0
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build();repo=ShiftRepository(db,ShiftEngine(clock),clock,onChanged={otherEffects++},onHistoricalChanged={redraws++},onScheduleChanged={otherEffects++},onSessionDeleted={otherEffects++})}
    @After fun close(){db.close()}
    @Test fun importsSixThenSecondImportDoesNothing()=runBlocking {
        val first=repo.importBackfill(dates,zone);assertEquals(6,first.imported.size);assertTrue(first.skipped.isEmpty());val before=repo.sessions.first()
        val again=repo.importBackfill(dates,zone);assertTrue(again.imported.isEmpty());assertEquals(6,again.skipped.size);assertTrue(again.skipped.all{it.status==BackfillStatus.DUPLICATE});assertEquals(before,repo.sessions.first());assertEquals(1,redraws);assertEquals(0,otherEffects)
    }
    @Test fun subsetThenRemainderIsIdempotent()=runBlocking {
        assertEquals(2,repo.importBackfill(dates.take(2).toSet(),zone).imported.size)
        assertEquals(4,repo.importBackfill(dates,zone).imported.size);assertEquals(6,repo.sessions.first().size)
        assertTrue(repo.importBackfill(emptySet(),zone).imported.isEmpty());assertEquals(2,redraws)
    }
    @Test fun sameDayManualAndOverlappingRecordsAreSkippedNotReplaced()=runBlocking {
        val a=PUBLIX_BACKFILL[0].input.session(zone,clock.instant());val sameDay=a.copy(id=70,clockIn=a.clockIn.minusSeconds(7200),clockOut=a.clockIn.minusSeconds(3600))
        val b=PUBLIX_BACKFILL[1].input.session(zone,clock.instant()).copy(id=71,clockOut=PUBLIX_BACKFILL[1].input.session(zone,clock.instant()).clockOut!!.minusSeconds(60))
        db.shifts().insert(sameDay.entity());db.shifts().insert(b.entity())
        val r=repo.importBackfill(dates,zone);assertEquals(4,r.imported.size);assertEquals(listOf(BackfillStatus.SAME_DAY,BackfillStatus.OVERLAP),r.skipped.map{it.status});assertEquals(sameDay,repo.session(70).first());assertEquals(b,repo.session(71).first())
    }
    @Test fun conflictCreatedAfterPreviewIsRecheckedInTransaction()=runBlocking {
        assertEquals(BackfillStatus.READY,reviewBackfill(PUBLIX_BACKFILL[0],repo.sessions.first(),zone,clock.instant()).status)
        repo.addHistorical(PUBLIX_BACKFILL[0].input,zone)
        assertEquals(5,repo.importBackfill(dates,zone).imported.size);assertEquals(6,repo.sessions.first().size)
    }
    @Test fun concurrentImportsCannotDuplicate()=runBlocking {
        val results=awaitAll(async(Dispatchers.IO){repo.importBackfill(dates,zone)},async(Dispatchers.IO){repo.importBackfill(dates,zone)})
        assertEquals(6,results.sumOf{it.imported.size});assertEquals(6,repo.sessions.first().size);assertEquals(1,redraws)
    }
    @Test fun failureDuringSecondInsertionRollsBackWholeBatch()=runBlocking {
        val second=PUBLIX_BACKFILL[1].input.session(zone,clock.instant()).clockIn.toEpochMilli()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_backfill BEFORE INSERT ON work_sessions WHEN NEW.clockIn=$second BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END")
        assertTrue(runCatching{repo.importBackfill(dates,zone)}.isFailure)
        assertTrue(repo.sessions.first().isEmpty());assertEquals(0,redraws)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_backfill")
        assertEquals(6,repo.importBackfill(dates,zone).imported.size)
    }
    @Test fun zeroLengthRecordAtWindowStartIsStillSameDayConflict()=runBlocking {
        val start=PUBLIX_BACKFILL.first().date.atStartOfDay(zone).toInstant()
        db.shifts().insert(WorkSession(clockIn=start,clockOut=start,state=ShiftState.COMPLETE).entity())
        val result=repo.importBackfill(setOf(PUBLIX_BACKFILL.first().date),zone)
        assertTrue(result.imported.isEmpty());assertEquals(BackfillStatus.SAME_DAY,result.skipped.single().status)
    }
    @Test fun invalidSelectedDateCannotWrite()=runBlocking {assertTrue(runCatching{repo.importBackfill(dates+LocalDate.of(2026,10,3),zone)}.isFailure);assertTrue(repo.sessions.first().isEmpty())}
    @Test fun activeCurrentWeekAndSchedulesAreUntouched()=runBlocking {
        val historic=repo.addHistorical(HistoricalShiftInput(LocalDate.of(2026,10,3),LocalTime.of(4,0),LocalTime.of(13,0)),zone)
        val active=WorkSession(id=100,clockIn=clock.instant().minusSeconds(3600));db.shifts().insert(active.entity())
        val schedule=ScheduledShift(id=200,date=LocalDate.of(2026,10,12),scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0));repo.save(schedule)
        val before=repo.sessions.first();val plans=repo.schedule.first();val effects=otherEffects
        repo.importBackfill(dates,zone)
        before.forEach{assertEquals(it,repo.session(it.id).first())};assertEquals(plans,repo.schedule.first());assertEquals(effects,otherEffects);assertEquals(active,repo.latestSession.first());assertEquals(historic,repo.session(historic).first()!!.id)
    }
    @Test fun priorDayActiveOverlapCannotBeBypassed()=runBlocking {
        val active=WorkSession(clockIn=PUBLIX_BACKFILL.first().date.minusDays(1).atStartOfDay(zone).toInstant());db.shifts().insert(active.entity())
        val r=repo.importBackfill(dates,zone);assertTrue(r.imported.isEmpty());assertEquals(6,r.skipped.size);assertEquals(1,repo.sessions.first().size)
    }
    @Test fun analyticsAndPaycheckEstimatesReactWhilePayrollFactsSurvive()=runBlocking {
        val payroll=PayrollRepository(db);val period=AnalyticsPeriod.week(LocalDate.of(2026,9,26))
        payroll.save(Paycheck(periodStart=period.start,periodEnd=period.endExclusive.minusDays(1),reportedHours="35.39".toBigDecimal(),grossCents=56624,netCents=49610,deposits=listOf(PayrollDeposit(cents=49610))))
        val facts=payroll.paychecks.first().single();val rates=MutableStateFlow(DEFAULT_PAY_RATES);val beforeRates=rates.value.toList()
        val analytics=AnalyticsRepository(repo,rates,MutableStateFlow(WeeklyTargetSettings()),ShiftEngine(clock));var latest:WorkAnalytics?=null
        val job=launch(start=CoroutineStart.UNDISPATCHED){analytics.observe(period,zone,flowOf(clock.instant())).collect{latest=it}}
        suspend fun awaitCount(n:Int)=withTimeout(5000){while(latest?.completedSessions!=n)delay(10)}
        try {
            awaitCount(0);repo.importBackfill(dates,zone);awaitCount(5)
            assertEquals(5,latest!!.workedDays);assertEquals(Duration.ofMinutes(2123),latest!!.paid);assertEquals(56612L,latest!!.grossCents)
            assertEquals(12L,PayrollReconciliation(facts,latest!!).grossDifference);assertEquals(facts,payroll.paychecks.first().single());assertEquals(beforeRates,rates.value)
            val first=analytics.observe(AnalyticsPeriod.week(LocalDate.of(2026,9,19)),zone,flowOf(clock.instant())).first()
            assertEquals(1,first.completedSessions);assertEquals(1,first.workedDays);assertEquals(Duration.ofMinutes(129),first.paid);assertEquals(3440L,first.grossCents)
            assertEquals(PUBLIX_BACKFILL.drop(1).map{it.date},latest!!.days.filter{it.paidDuration>Duration.ZERO}.map{it.date})
        } finally {job.cancelAndJoin()}
    }
    @Test fun noAutomaticPayrollRecordsOrSchemaChange()=runBlocking {repo.importBackfill(dates,zone);assertTrue(PayrollRepository(db).paychecks.first().isEmpty());assertEquals(6,db.openHelper.readableDatabase.version)}
    @Test fun persistedImportIsRecognizedAfterDatabaseReopen()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="backfill-restart.db";context.deleteDatabase(name)
        try {repeat(2){pass->val disk=Room.databaseBuilder(context,ShiftDatabase::class.java,name).build();try {
            val records=ShiftRepository(disk,ShiftEngine(clock),clock);val r=records.importBackfill(dates,zone);assertEquals(if(pass==0)6 else 0,r.imported.size);assertEquals(6,records.sessions.first().size)
        }finally{disk.close()}}}finally{context.deleteDatabase(name)}
    }
}
