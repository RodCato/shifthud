package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.*
import com.shifthud.data.local.entity.*
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import com.shifthud.domain.usecase.ShiftEngine
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
class GlobalLunchPlanTest {
    private val zone=ZoneOffset.UTC
    private val now=Instant.parse("2026-10-05T02:00:00Z")
    private val monday=LocalDate.of(2026,10,5)
    private val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build()
    private var settings=PlannerSettings()
    private val repo=ShiftRepository(db,ShiftEngine(),Clock.fixed(now,zone),plannerSettings={settings})
    private fun shift(id:Long,days:Long=0,lunch:Int?=60,global:Boolean=true)=ScheduledShift(id,monday.plusDays(days),LocalTime.of(4,0),LocalTime.of(13,0),lunch,"Keep notes",global)
    @After fun close(){db.close()}
    @Test fun newShiftInheritsAndSavedDefaultDoesNotRewriteExisting()=runBlocking {
        settings=PlannerSettings(50)
        repo.save(shift(1,lunch=null));val original=repo.schedule.first().single()
        assertEquals(50,original.plannedLunchMinutes);assertTrue(original.usesGlobalLunch)
        settings=PlannerSettings(45);repo.save(shift(2,1,lunch=null))
        assertEquals(original,repo.schedule.first().first());assertEquals(45,repo.schedule.first().last().plannedLunchMinutes)
    }
    @Test fun legacyImplicitPlansKeepTheirEffectiveLunchWhenDefaultChanges()=runBlocking {
        db.shifts().save(shift(1,lunch=null,global=false).entity())
        settings=PlannerSettings(60,45,LunchStrategy.SHORTER)
        val before=clockOutPlan(emptyList(),repo.schedule.first(),Duration.ofHours(8),settings,now,zone)
        repo.preserveLegacyLunchPlans(settings);settings=settings.copy(plannedMinutes=50)
        val after=clockOutPlan(emptyList(),repo.schedule.first(),Duration.ofHours(8),settings,now,zone)
        assertEquals(before.projected,after.projected);assertEquals(before.clockOut,after.clockOut)
    }
    @Test fun inheritedNewShiftsStillSupportExistingShorterLunchStrategy()=runBlocking {
        (0..4).forEach { repo.save(shift(it+1L,it.toLong(),null)) }
        settings=PlannerSettings(60,45,LunchStrategy.SHORTER)
        val plan=clockOutPlan(emptyList(),repo.schedule.first(),Duration.ofHours(40),settings,now,zone)
        assertEquals(Instant.parse("2026-10-09T12:00:00Z"),plan.clockOut)
    }
    @Test fun defaultPreferencesSurviveRecreation()=runBlocking {
        val p=ShiftPreferences(RuntimeEnvironment.getApplication());p.setPlanner(PlannerSettings(50))
        assertEquals(50,ShiftPreferences(RuntimeEnvironment.getApplication()).plannerSettings.first().plannedMinutes)
    }
    @Test fun bulkPreservesOverridesIdsNotesTimesAndRecords()=runBlocking {
        val all=listOf(shift(1),shift(2,1,31,false),shift(3,-2),shift(4,5))
        all.forEach{repo.save(it)}
        val selected=lunchPlanCandidates(repo.schedule.first(),emptyList(),now,zone,false)
        assertEquals(listOf(1L),selected.map{it.id})
        repo.applyLunchPlan(selected,50,false,LocalDate.of(2026,10,3),zone)
        assertEquals(all.map{if(it.id==1L)it.copy(plannedLunchMinutes=50,lunchPlanApplied=true) else it},repo.schedule.first().sortedBy{it.id})
        assertTrue(db.shifts().allSessions().isEmpty())
    }
    @Test fun explicitOverrideReplacementAndRepeatApplication()=runBlocking {
        repo.save(shift(1,lunch=31,global=false))
        assertTrue(lunchPlanCandidates(repo.schedule.first(),emptyList(),now,zone,false).isEmpty())
        repo.applyLunchPlan(repo.schedule.first(),50,true,LocalDate.of(2026,10,3),zone)
        assertTrue(repo.schedule.first().single().usesGlobalLunch)
        repo.applyLunchPlan(repo.schedule.first(),45,false,LocalDate.of(2026,10,3),zone)
        assertEquals(45,repo.schedule.first().single().plannedLunchMinutes)
    }
    @Test fun completedAndActiveAssociationsProtectedEvenIfScheduleMovedFuture()=runBlocking {
        listOf(shift(1),shift(2,1),shift(3,2)).forEach { repo.save(it) }
        val records=listOf(WorkSession(1,1,now.minusSeconds(3600),clockOut=now.minusSeconds(60),state=ShiftState.COMPLETE),WorkSession(2,2,now.minusSeconds(30)))
        records.forEach{db.shifts().insert(it.entity())}
        val before=db.shifts().allSessions()
        val selected=lunchPlanCandidates(repo.schedule.first(),records,now,zone,false)
        assertEquals(listOf(3L),selected.map{it.id})
        repo.applyLunchPlan(selected,50,false,LocalDate.of(2026,10,3),zone)
        assertEquals(before,db.shifts().allSessions())
        assertEquals(listOf(60,60,50),repo.schedule.first().map{it.plannedLunchMinutes})
    }
    @Test fun staleConfirmationRejectsAllWrites()=runBlocking {
        repo.save(shift(1));repo.save(shift(2,1));val expected=repo.schedule.first()
        repo.save(shift(2,1).copy(notes="Changed"));val before=repo.schedule.first()
        assertTrue(runCatching{repo.applyLunchPlan(expected,50,false,LocalDate.of(2026,10,3),zone)}.isFailure)
        assertEquals(before,repo.schedule.first())
    }
    @Test fun newlyActiveShiftRejectsOldPreview()=runBlocking {
        repo.save(shift(1));val expected=repo.schedule.first()
        db.shifts().insert(WorkSession(1,1,now).entity())
        assertTrue(runCatching{repo.applyLunchPlan(expected,50,false,LocalDate.of(2026,10,3),zone)}.isFailure)
        assertEquals(expected,repo.schedule.first())
    }
    @Test fun hypotheticalFiftyMinuteLunchLeavesFridayAtTwelveTen() {
        val all=(0..4).map{shift(it+1L,it.toLong())}
        val proposed=hypotheticalLunchPlan(all,lunchPlanCandidates(all,emptyList(),now,zone,false),50)
        val plan=clockOutPlan(emptyList(),proposed,Duration.ofHours(40),settings,now,zone)
        assertEquals(Duration.ofMinutes(2450),plan.projected)
        assertEquals(Instant.parse("2026-10-09T12:10:00Z"),plan.clockOut)
        assertEquals(Instant.parse("2026-10-09T13:00:00Z"),clockOutPlan(emptyList(),all,Duration.ofHours(40),settings,now,zone).clockOut)
        assertTrue(all.all{it.plannedLunchMinutes==60})
    }
    @Test fun minimumAndWeekRolloverRejectWithoutChanges()=runBlocking {
        repo.save(shift(1));val before=repo.schedule.first()
        assertTrue(runCatching{repo.applyLunchPlan(before,20,false,LocalDate.of(2026,10,3),zone)}.isFailure)
        assertTrue(runCatching{repo.applyLunchPlan(before,50,false,LocalDate.of(2026,10,10),zone)}.isFailure)
        assertEquals(before,repo.schedule.first())
    }
}
