package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.calendar.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.*
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
class CalendarRepositoryTest {
    private lateinit var db: ShiftDatabase
    private lateinit var repo: ShiftRepository
    private val zone=ZoneOffset.UTC
    private val now=Instant.parse("2026-10-06T18:00:00Z")
    private val engine=ShiftEngine(Clock.fixed(now,zone))
    private val estimator=PayEstimator(engine)
    private val month=YearMonth.of(2026,10)
    private val range=calendarRange(month)
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build();repo=ShiftRepository(db,engine,Clock.fixed(now,zone))}
    @After fun close(){db.close()}
    private fun input()=HistoricalShiftInput(LocalDate.of(2026,10,3),LocalTime.of(4,0),LocalTime.of(13,0),LocalTime.of(9,47),LocalTime.of(10,41))
    private fun source()=combine(repo.scheduleBetween(range.start,range.endExclusive),repo.sessionsBetween(range.start,range.endExclusive,zone)){schedule,sessions->workCalendar(month,schedule,sessions,DEFAULT_PAY_RATES,now,zone,estimator)}
    @Test fun monthQueriesExcludeRecordsOutsideVisibleGridAndKeepEdges()=runBlocking {
        val dates=listOf(range.start.minusDays(1),range.start,range.endExclusive.minusDays(1),range.endExclusive)
        dates.forEach { date ->
            val start=date.atTime(4,0).toInstant(zone)
            db.shifts().insert(WorkSession(clockIn=start,clockOut=start.plusSeconds(3600),state=ShiftState.COMPLETE).entity())
            repo.save(ScheduledShift(date=date,scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0)))
        }
        assertEquals(dates.subList(1,3),repo.sessionsBetween(range.start,range.endExclusive,zone).first().map{it.clockIn.atZone(zone).toLocalDate()})
        assertEquals(dates.subList(1,3),repo.scheduleBetween(range.start,range.endExclusive).first().map{it.date})
    }
    @Test fun localRangeUsesCalendarMidnightsAcrossDst()=runBlocking {
        val chicago=ZoneId.of("America/Chicago")
        val date=LocalDate.of(2026,11,1)
        listOf(date.atStartOfDay(chicago).toInstant().minusMillis(1),date.atStartOfDay(chicago).toInstant(),date.plusDays(1).atStartOfDay(chicago).toInstant().minusMillis(1),date.plusDays(1).atStartOfDay(chicago).toInstant()).forEach { start->db.shifts().insert(WorkSession(clockIn=start,clockOut=start.plusSeconds(1),state=ShiftState.COMPLETE).entity()) }
        assertEquals(2,repo.sessionsBetween(date,date.plusDays(1),chicago).first().size)
    }
    @Test fun addingEditingDeletingUpdatesCalendarAndWeekReactively()=runBlocking {
        var latest:List<WorkCalendarDay> = emptyList()
        val job=launch(start=CoroutineStart.UNDISPATCHED){source().collect{latest=it}}
        suspend fun awaitPaid(minutes:Long)=withTimeout(5000){while(latest.isEmpty()||latest.single{it.date==input().date}.paidDuration!=Duration.ofMinutes(minutes))delay(10)}
        try {
            awaitPaid(0)
            val id=repo.addHistorical(input(),zone);awaitPaid(486)
            val saved=repo.session(id).first()!!
            assertTrue(latest.single{it.date==input().date}.hasCompletedSession)
            assertEquals(12960L,estimator.weekBreakdown(repo.sessionsBetween(range.start,range.endExclusive,zone).first(),DEFAULT_PAY_RATES,now,zone).grossCents)
            repo.correct(saved,TimeEvent.CLOCK_OUT,saved.clockOut!!.minusSeconds(3600));awaitPaid(426)
            assertEquals(11360L,latest.single{it.date==input().date}.estimatedGross)
            repo.deleteHistorical(repo.session(id).first()!!);awaitPaid(0)
            assertFalse(latest.single{it.date==input().date}.hasCompletedSession)
            assertEquals(0L,estimator.weekBreakdown(repo.sessionsBetween(range.start,range.endExclusive,zone).first(),DEFAULT_PAY_RATES,now,zone).grossCents)
        } finally {job.cancelAndJoin()}
    }
    @Test fun clockOutChangesActiveIndicatorToCompleted()=runBlocking {
        repo.clockIn()
        assertTrue(source().first().single{it.date==LocalDate.of(2026,10,6)}.hasActiveSession)
        val active=repo.latestSession.first()!!
        repo.transition(active.id,active.state,engine::clockOut)
        val day=source().first().single{it.date==LocalDate.of(2026,10,6)}
        assertFalse(day.hasActiveSession);assertTrue(day.hasCompletedSession)
    }
    @Test fun scheduleEditsUpdateCalendarWithoutCreatingSessions()=runBlocking {
        val shift=ScheduledShift(date=LocalDate.of(2026,10,9),scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0))
        repo.save(shift)
        val first=source().first().single{it.date==shift.date}
        assertEquals("○",first.indicator);assertTrue(repo.sessions.first().isEmpty())
        val saved=repo.schedule.first().single()
        repo.save(saved.copy(scheduledStart=LocalTime.of(5,0)))
        assertEquals(LocalTime.of(5,0),source().first().single{it.date==shift.date}.scheduledShifts.single().scheduledStart)
        assertTrue(repo.sessions.first().isEmpty())
    }
    @Test fun readingCalendarAndBreakdownNeverRewritesStoredData()=runBlocking {
        val id=repo.addHistorical(input(),zone)
        val original=repo.session(id).first()
        val before=repo.sessions.first()
        val days=source().first()
        estimator.weekBreakdown(days.flatMap{it.workSessions},DEFAULT_PAY_RATES,now,zone)
        assertEquals(before,repo.sessions.first());assertEquals(original,repo.session(id).first());assertEquals(5,db.openHelper.readableDatabase.version)
    }
    @Test fun directRecordFlowFindsSessionOutsideDisplayedMonth()=runBlocking {
        val id=repo.addHistorical(input().copy(date=LocalDate.of(2026,8,3)),zone)
        assertTrue(repo.sessionsBetween(range.start,range.endExclusive,zone).first().isEmpty())
        assertEquals(id,repo.session(id).first()!!.id)
    }
}
