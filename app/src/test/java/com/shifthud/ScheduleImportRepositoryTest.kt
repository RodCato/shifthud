package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.*
import com.shifthud.domain.model.*
import com.shifthud.domain.scheduleimport.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.domain.planner.*
import com.shifthud.domain.payroll.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ScheduleImportRepositoryTest {
    private lateinit var db:ShiftDatabase
    private lateinit var repo:ShiftRepository
    private var widgets=0;private var reminders=0
    private val week=LocalDate.of(2026,10,17)
    private val clock=Clock.fixed(Instant.parse("2026-10-17T05:00:00Z"),ZoneOffset.UTC)
    private fun open() {db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java,"schedule-import.db").build();repo=ShiftRepository(db,ShiftEngine(clock),clock,onChanged={widgets++},onHistoricalChanged={widgets++},onScheduleChanged={reminders++})}
    @Before fun setup(){RuntimeEnvironment.getApplication().deleteDatabase("schedule-import.db");open()}
    @After fun close(){db.close();RuntimeEnvironment.getApplication().deleteDatabase("schedule-import.db")}
    private fun s(day:Long=0)=ScheduledShift(date=week.plusDays(day),scheduledStart=LocalTime.of(8,0),scheduledEnd=LocalTime.of(17,0),plannedLunchMinutes=60)
    @Test fun batchRefreshesRemindersWidgetsAndPlannerOnce()=runBlocking {
        val result=repo.importSchedule((0L..4L).map{ScheduleImportChoice(s(it))},emptyList(),week)
        assertEquals(5,result.imported);assertEquals(1,widgets);assertEquals(1,reminders)
        val plan=clockOutPlan(emptyList(),repo.schedule.first(),Duration.ofHours(40),PlannerSettings(),clock.instant(),ZoneOffset.UTC)
        assertEquals(Duration.ofHours(40),plan.projected)
    }
    @Test fun repeatImportSkipsExactDuplicates()=runBlocking {
        val choices=listOf(ScheduleImportChoice(s()))
        repo.importSchedule(choices,emptyList(),week)
        val r=repo.importSchedule(choices,repo.schedule.first(),week)
        assertEquals(0,r.imported);assertEquals(1,r.duplicates);assertEquals(1,repo.schedule.first().size)
    }
    @Test fun replacementPreservesStableIdLunchAndNotes()=runBlocking {
        repo.save(s().copy(plannedLunchMinutes=45,notes="My override"));val old=repo.schedule.first().single()
        val updated=s().copy(scheduledStart=LocalTime.of(9,0))
        val r=repo.importSchedule(listOf(ScheduleImportChoice(updated,old)),listOf(old),week)
        val now=repo.schedule.first().single();assertEquals(1,r.updated);assertEquals(old.id,now.id);assertEquals(45,now.plannedLunchMinutes);assertEquals(old.notes,now.notes)
    }
    @Test fun stalePreviewRejectsEverything()=runBlocking {
        repo.save(s());val result=runCatching{repo.importSchedule(listOf(ScheduleImportChoice(s(1))),emptyList(),week)}
        assertTrue(result.isFailure);assertEquals(1,repo.schedule.first().size)
    }
    @Test fun secondInvalidRowRollsBackFirstInsertAndRefreshesNothing()=runBlocking {
        assertTrue(runCatching{repo.importSchedule(listOf(ScheduleImportChoice(s()),ScheduleImportChoice(s(7))),emptyList(),week)}.isFailure)
        assertTrue(repo.schedule.first().isEmpty());assertEquals(0,widgets);assertEquals(0,reminders)
    }
    @Test fun overlappingSelectedRowsRollBack()=runBlocking {
        val conflicting=s().copy(scheduledStart=LocalTime.of(10,0))
        assertTrue(runCatching{repo.importSchedule(listOf(ScheduleImportChoice(s()),ScheduleImportChoice(conflicting)),emptyList(),week)}.isFailure)
        assertTrue(repo.schedule.first().isEmpty())
    }
    @Test fun changedExistingNeedsExplicitReplacement()=runBlocking {
        repo.save(s());val old=repo.schedule.first()
        assertTrue(runCatching{repo.importSchedule(listOf(ScheduleImportChoice(s().copy(scheduledStart=LocalTime.of(9,0)))),old,week)}.isFailure)
        assertEquals(old,repo.schedule.first())
    }
    @Test fun selectedSubsetPersistsAcrossRestart()=runBlocking {
        repo.importSchedule(listOf(ScheduleImportChoice(s(1)),ScheduleImportChoice(s(3))),emptyList(),week)
        val before=repo.schedule.first();db.close();open();assertEquals(before,repo.schedule.first());assertEquals(listOf(week.plusDays(1),week.plusDays(3)),before.map{it.date})
    }
    @Test fun workAndPayrollUnchangedAndWorkedScheduleProtected()=runBlocking {
        repo.save(s());repo.clockIn();val sessions=repo.sessions.first();val old=repo.schedule.first().single()
        val payroll=PayrollRepository(db);payroll.save(Paycheck(periodStart=week,periodEnd=week.plusDays(6)));val facts=payroll.paychecks.first()
        repo.importSchedule(listOf(ScheduleImportChoice(s(1))),listOf(old),week)
        assertEquals(sessions,repo.sessions.first());assertEquals(facts,payroll.paychecks.first())
        assertTrue(runCatching{repo.importSchedule(listOf(ScheduleImportChoice(s().copy(scheduledStart=LocalTime.of(9,0)),old)),repo.schedule.first(),week)}.isFailure)
        assertEquals(sessions,repo.sessions.first())
    }
    @Test fun importUsesReadOnlyRedrawInsteadOfLiveLunchRefresh()=runBlocking {
        var redraw=0
        val isolated=ShiftRepository(db,ShiftEngine(clock),clock,onChanged={error("Live refresh must not run")},onHistoricalChanged={redraw++})
        isolated.importSchedule(listOf(ScheduleImportChoice(s())),emptyList(),week)
        assertEquals(1,redraw)
    }
    @Test fun reminderFailureDoesNotReportCommittedImportAsFailed()=runBlocking {
        val isolated=ShiftRepository(db,ShiftEngine(clock),clock,onScheduleChanged={error("Unavailable")})
        val result=isolated.importSchedule(listOf(ScheduleImportChoice(s())),emptyList(),week)
        assertEquals(1,result.imported);assertTrue(result.refreshDeferred);assertEquals(1,repo.schedule.first().size)
    }

    @Test fun deletingSchedulePreservesCompletedPunchesPayrollAndPaidDuration()=runBlocking {
        repo.save(s());val scheduled=repo.schedule.first().single()
        val start=week.atTime(8,0).toInstant(ZoneOffset.UTC)
        val completed=WorkSession(801,scheduled.id,start,start.plusSeconds(4*3600),start.plusSeconds(5*3600),start.plusSeconds(9*3600),ShiftState.COMPLETE)
        db.shifts().insert(com.shifthud.data.local.entity.WorkSessionEntity(801,scheduled.id,start.toEpochMilli(),completed.lunchStart!!.toEpochMilli(),completed.lunchEnd!!.toEpochMilli(),completed.clockOut!!.toEpochMilli(),ShiftState.COMPLETE.name))
        val payroll=PayrollRepository(db);payroll.save(Paycheck(periodStart=week,periodEnd=week.plusDays(6),grossCents=12800))
        val facts=payroll.paychecks.first();val before=repo.sessions.first().single()
        val end=start.plusSeconds(10*3600);val paid=ShiftEngine().durations(before,360,end).paid
        val previousReminders=reminders
        repo.delete(scheduled.id)
        assertTrue(repo.schedule.first().isEmpty());assertEquals(previousReminders+1,reminders)
        val after=repo.sessions.first().single()
        assertEquals(before.copy(scheduledShiftId=null),after)
        assertEquals(paid,ShiftEngine().durations(after,360,end).paid)
        assertEquals(facts,payroll.paychecks.first())
    }

}
