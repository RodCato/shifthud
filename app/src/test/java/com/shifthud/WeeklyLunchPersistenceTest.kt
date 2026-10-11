package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.*
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.planner.*
import com.shifthud.domain.model.*
import com.shifthud.domain.scheduleimport.*
import com.shifthud.domain.usecase.ShiftEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class WeeklyLunchPersistenceTest {
    @Test fun plansSurviveRecreationAndNeverModifySchedulesOrPunches()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val week=LocalDate.of(2026,10,17)
        var sideEffects=0
        val prefs=ShiftPreferences(context) { sideEffects++ }
        val db=Room.inMemoryDatabaseBuilder(context,ShiftDatabase::class.java).build()
        try {
            val repo=ShiftRepository(db,ShiftEngine())
            repo.save(ScheduledShift(1,week,LocalTime.of(16,0),LocalTime.of(23,0),90,"Legacy override",lunchPlanApplied=true))
            val before=repo.schedule.first();val records=repo.sessions.first()
            prefs.setWeeklyLunch(week,35);prefs.setWeeklyLunch(week.plusWeeks(1),45)
            assertEquals(before,repo.schedule.first());assertEquals(records,repo.sessions.first());assertEquals(0,sideEffects)
            val recreated=ShiftPreferences(context).weeklyPlannerSettings.first()
            assertEquals(35,recreated.weeklyMinutes(week));assertEquals(45,recreated.weeklyMinutes(week.plusWeeks(1)))
            assertEquals(60,recreated.weeklyMinutes(week.plusWeeks(2)))
            assertTrue(recreated.dynamicWeekly)
            assertEquals(90,repo.schedule.first().single().plannedLunchMinutes)
        } finally { db.close() }
    }
    @Test fun importAndEditPersistMetadataWithoutChangingWeeklyPlan()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="weekly-import-restart.db"
        context.deleteDatabase(name)
        val week=LocalDate.of(2026,10,17)
        val prefs=ShiftPreferences(context);prefs.setWeeklyLunch(week,35)
        var db=Room.databaseBuilder(context,ShiftDatabase::class.java,name).build()
        try {
            var repo=ShiftRepository(db,ShiftEngine())
            val candidate=extractSchedule("10/17/2026\nSat Oct 17 4 PM–11 PM Net 6 hours").rows.single().shift()!!
            repo.importSchedule(listOf(ScheduleImportChoice(candidate)),emptyList(),week)
            val saved=repo.schedule.first().single()
            assertNull(saved.plannedLunchMinutes);assertEquals(360,saved.employerNetMinutes)
            // Editing dates/times/notes preserves employer evidence and legacy fields.
            repo.save(saved.copy(notes="Edited note"))
            db.close();db=Room.databaseBuilder(context,ShiftDatabase::class.java,name).build();repo=ShiftRepository(db,ShiftEngine())
            val reopened=repo.schedule.first().single()
            assertEquals(saved.id,reopened.id);assertEquals("Edited note",reopened.notes)
            assertEquals(360,reopened.employerNetMinutes);assertEquals(60,reopened.employerLunchMinutes)
            assertTrue(repo.sessions.first().isEmpty())
            val settings=ShiftPreferences(context).weeklyPlannerSettings.first()
            assertEquals(35,settings.weeklyMinutes(week))
            val plan=clockOutPlan(emptyList(),listOf(reopened),Duration.ofHours(40),settings,week.atStartOfDay().toInstant(ZoneOffset.UTC),ZoneOffset.UTC)
            assertEquals(Duration.ofMinutes(385),plan.projected)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun newScheduleUsesWeeklyDefaultWithoutPerShiftLunchSnapshot()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val prefs=ShiftPreferences(context)
        prefs.setPlanner(PlannerSettings(plannedMinutes=50))
        val db=Room.inMemoryDatabaseBuilder(context,ShiftDatabase::class.java).build()
        try {
            val repo=ShiftRepository(db,ShiftEngine(),plannerSettings={prefs.weeklyPlannerSettings.first()})
            val week=LocalDate.of(2028,1,1)
            repo.save(ScheduledShift(date=week,scheduledStart=LocalTime.of(8,0),scheduledEnd=LocalTime.of(17,0)))
            val s=repo.schedule.first().single()
            assertNull(s.plannedLunchMinutes)
            assertEquals(Duration.ofMinutes(50),prefs.weeklyPlannerSettings.first().lunch(s,null))
            prefs.setWeeklyLunch(week,35)
            assertEquals(s,repo.schedule.first().single())
            assertEquals(Duration.ofMinutes(35),prefs.weeklyPlannerSettings.first().lunch(s,null))
        } finally { db.close() }
    }
    @Test fun backupPreferenceCodecRejectsNonSaturdayAndInvalidDurations() {
        listOf("{\"2026-10-18\":35}","{\"2026-10-17\":30}","{\"2026-10-17\":35.5}").forEach {
            assertTrue(runCatching { ShiftPreferences.decodeWeeklyLunches(it) }.isFailure)
        }
        assertTrue(ShiftPreferences.decodeWeeklyLunches(null).isEmpty())
    }
}
