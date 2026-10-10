package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.data.repository.*
import com.shifthud.domain.analytics.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
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
class AnalyticsRepositoryTest {
    private lateinit var db:ShiftDatabase
    private lateinit var records:ShiftRepository
    private lateinit var analytics:AnalyticsRepository
    private val now=Instant.parse("2026-10-10T18:00:00Z")
    private val zone=ZoneId.of("America/Chicago")
    private val week=AnalyticsPeriod.week(LocalDate.of(2026,10,3))
    private val ticks=MutableStateFlow(now)
    private val rates=MutableStateFlow(DEFAULT_PAY_RATES)
    private val target=MutableStateFlow(WeeklyTargetSettings())
    private var sideEffects=0
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build()
        val engine=ShiftEngine(Clock.fixed(now,zone))
        records=ShiftRepository(db,engine,Clock.fixed(now,zone),onChanged={sideEffects++},onHistoricalChanged={sideEffects++})
        analytics=AnalyticsRepository(records,rates,target,engine)
    }
    @After fun close(){db.close()}
    private fun input()=HistoricalShiftInput(week.start,LocalTime.of(4,0),LocalTime.of(13,0),LocalTime.of(9,0),LocalTime.of(10,0))
    @Test fun insertionCorrectionDeletionUpdateSameSubscribedFlow()=runBlocking {
        var latest:WorkAnalytics?=null
        val job=launch(start=CoroutineStart.UNDISPATCHED){analytics.observe(week,zone,ticks).collect{latest=it}}
        suspend fun awaitMinutes(n:Long)=withTimeout(5000){while(latest?.paid!=Duration.ofMinutes(n))delay(10)}
        try {
            awaitMinutes(0);val id=records.addHistorical(input(),zone);awaitMinutes(480)
            assertEquals(id,latest!!.breakdown.contributions.single().session.id)
            val s=records.session(id).first()!!;records.correct(s,TimeEvent.CLOCK_OUT,s.clockOut!!.minusSeconds(3600));awaitMinutes(420)
            assertEquals(11200L,latest!!.grossCents)
            records.deleteHistorical(records.session(id).first()!!);awaitMinutes(0);assertEquals(0,latest!!.completedSessions)
        } finally{job.cancelAndJoin()}
    }
    @Test fun rangeQueriesExcludeAdjacentDatesAndKeepOvernightStart()=runBlocking {
        for(date in listOf(week.start.minusDays(1),week.start,week.endExclusive.minusDays(1),week.endExclusive)) {
            val start=date.atTime(23,0).atZone(zone).toInstant();db.shifts().insert(WorkSession(clockIn=start,clockOut=start.plusSeconds(3600),state=ShiftState.COMPLETE).entity())
        }
        val result=analytics.observe(week,zone,ticks).first();assertEquals(2,result.completedSessions);assertEquals(Duration.ofHours(2),result.paid)
    }
    @Test fun activeAccrualAndRateChangesAreReactive()=runBlocking {
        val start=now.minusSeconds(3600);db.shifts().insert(WorkSession(clockIn=start).entity())
        val period=AnalyticsPeriod.week(start.atZone(zone).toLocalDate())
        assertEquals(Duration.ofHours(1),analytics.observe(period,zone,ticks).first().paid)
        ticks.value=now.plusSeconds(60);assertEquals(Duration.ofMinutes(61),analytics.observe(period,zone,ticks).first().paid)
        rates.value=listOf(PayRate(LocalDate.MIN,2000));assertEquals(2033L,analytics.observe(period,zone,ticks).first().grossCents)
    }
    @Test fun readingNeverWritesOrChangesDashboardSelection()=runBlocking {
        records.addHistorical(input(),zone);records.clockIn()
        val before=records.sessions.first();val latest=records.latestSession.first();val schedule=records.schedule.first();val effects=sideEffects
        repeat(3){analytics.observe(week.move(-it.toLong()),zone,ticks).first();analytics.observe(AnalyticsPeriod.month(YearMonth.of(2026,10).minusMonths(it.toLong())),zone,ticks).first()}
        assertEquals(before,records.sessions.first());assertEquals(latest,records.latestSession.first());assertEquals(schedule,records.schedule.first());assertEquals(effects,sideEffects);assertEquals(5,db.openHelper.readableDatabase.version)
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Test fun historicalMonthInsertionAppearsInMonthNotCurrentWeek()=runBlocking {
        records.addHistorical(input().copy(date=LocalDate.of(2026,9,30)),zone)
        assertEquals(Duration.ofHours(8),analytics.observe(AnalyticsPeriod.month(YearMonth.of(2026,9)),zone,ticks).first().paid)
        assertEquals(Duration.ZERO,analytics.observe(week,zone,ticks).first().paid)
    }
}
