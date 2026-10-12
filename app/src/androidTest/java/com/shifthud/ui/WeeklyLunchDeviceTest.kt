package com.shifthud.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.planner.*
import com.shifthud.domain.scheduleimport.*
import com.shifthud.ui.settings.LunchPlanControl
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.*

/** Opt-in mutation only on a backed-up, empty emulator. Ordinary suite leaves user data untouched. */
class WeeklyLunchDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    private val week=LocalDate.of(2026,10,17)
    @Test fun previewSaveAndRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("phase")
        if(phase=="weekly_restart") {
            assertEquals(35,app.preferences.weeklyPlannerSettings.first().weeklyMinutes(week))
            assertEquals(48,app.preferences.weeklyPlannerSettings.first().weeklyMinutes(week.minusWeeks(1)))
            assertEquals(5,app.repository.schedule.first().size)
            assertTrue(app.repository.schedule.first().all { it.plannedLunchMinutes==null && it.employerLunchMinutes==60 })
            assertTrue(app.repository.sessions.first().isEmpty())
            return@runBlocking
        }
        if(phase!="weekly_lunch")return@runBlocking
        check(app.repository.schedule.first().isEmpty() && app.repository.sessions.first().isEmpty())
        val fixture="""10/17/2026
            |Sat Oct 17 4 PM–11 PM Net 6 hours
            |Sun Oct 18 10 AM–6 PM Net 7 hours
            |Mon Oct 19 8 AM–5 PM Net 8 hours
            |Tue Oct 20 8 AM–5 PM Net 8 hours
            |Wed Oct 21 8 AM–5 PM Net 8 hours""".trimMargin()
        app.repository.importSchedule(extractSchedule(fixture).rows.map { ScheduleImportChoice(it.shift()!!) },emptyList(),week)
        app.preferences.setPlanner(PlannerSettings())
        app.preferences.setWeeklyLunch(week.minusWeeks(1),48)
        val before=app.repository.schedule.first();val pay=app.payroll.paychecks.first()
        val vm=ShiftViewModel(app)
        ui.setContent { ShiftHudTheme { LunchPlanControl(vm,false) } }
        ui.onNodeWithText("Edit Lunch Plan").performClick()
        val current=com.shifthud.domain.pay.workWeekFor(LocalDate.now()).start
        check(current<=week)
        repeat(java.time.temporal.ChronoUnit.WEEKS.between(current,week).toInt()) { ui.onNodeWithText("Next week").performScrollTo().performClick() }
        listOf(60 to "37h 0m",45 to "38h 15m",35 to "39h 5m",31 to "39h 25m").forEach { (minutes,total) ->
            ui.onNodeWithText("Planned lunch minutes").performScrollTo().performTextReplacement(minutes.toString())
            ui.onNodeWithText("Projected weekly total: $total",substring=true).assertExists()
        }
        ui.onNodeWithText("Planned lunch minutes").performScrollTo().performTextReplacement("35")
        ui.onNodeWithText("SAVE WEEKLY PLAN").performScrollTo().performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("Weekly Lunch Plan").fetchSemanticsNodes().isEmpty() }
        assertEquals(35,app.preferences.weeklyPlannerSettings.first().weeklyMinutes(week))
        assertEquals(48,app.preferences.weeklyPlannerSettings.first().weeklyMinutes(week.minusWeeks(1)))
        assertEquals(before,app.repository.schedule.first());assertTrue(app.repository.sessions.first().isEmpty())
        assertEquals(pay,app.payroll.paychecks.first())
        app.backups.validate(com.shifthud.backup.BackupSnapshot.decode(app.backups.snapshot().bytes()))
    }
}
