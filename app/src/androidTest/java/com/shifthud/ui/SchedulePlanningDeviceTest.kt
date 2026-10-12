package com.shifthud.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import com.shifthud.ui.schedule.ScheduleScreen
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.*

class SchedulePlanningDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun selectedFutureWeekOpensExistingPlannerAndSurvivesRecreation() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
        val vm=ShiftViewModel(app)
        val week=LocalDate.of(2026,10,17)
        val shifts=(0L..4L).map { i -> ScheduledShift(i+1,week.plusDays(i),LocalTime.of(if(i==0L)16 else if(i==1L)10 else 8,0),LocalTime.of(if(i==0L)23 else if(i==1L)18 else 17,0),employerNetMinutes=if(i==0L)360 else if(i==1L)420 else 480) }
        val data=ShiftUiState(loaded=true,now=week.minusWeeks(1).atStartOfDay(ZoneId.systemDefault()).toInstant(),schedule=shifts)
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(ui)
        restoration.setContent { ShiftHudTheme { ScheduleScreen(data,vm,false) } }
        ui.onNodeWithContentDescription("Next week").performClick()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Scheduled: 37h 0m (employer-reported)"))
        ui.onNodeWithText("Scheduled: 37h 0m (employer-reported)").assertIsDisplayed()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("EDIT WEEKLY LUNCH PLAN"))
        ui.onNodeWithText("EDIT WEEKLY LUNCH PLAN").assertHasClickAction().performClick()
        ui.onNodeWithText("Saturday–Friday: 2026-10-17 – 2026-10-23").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("Saturday–Friday: 2026-10-17 – 2026-10-23").assertExists()
        ui.onNodeWithText("Planned lunch minutes").performScrollTo().performTextReplacement("31")
        ui.onNodeWithText("UNSAVED PREVIEW").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("CANCEL").performScrollTo().performClick()
        ui.onNodeWithTag("schedule-list").performScrollToIndex(0)
        ui.onNodeWithContentDescription("Previous week").performClick()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Scheduled: unknown — no saved shifts"))
        ui.onNodeWithText("Scheduled: unknown — no saved shifts").assertExists()
    }
    @Test fun saveFromScheduleAndVerifyAfterProcessRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("phase")
        if(phase !in setOf("schedule_plan_save","schedule_plan_restart")) return@runBlocking
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
        val week=LocalDate.of(2026,10,17)
        if(phase=="schedule_plan_restart") {
            assertEquals(31,app.preferences.weeklyPlannerSettings.first().weeklyMinutes(week))
            assertEquals(48,app.preferences.weeklyPlannerSettings.first().weeklyMinutes(week.minusWeeks(1)))
            assertEquals(5,app.repository.schedule.first().size)
            return@runBlocking
        }
        check(app.repository.schedule.first().size==5)
        app.preferences.setWeeklyLunch(week.minusWeeks(1),48)
        val schedules=app.repository.schedule.first();val sessions=app.repository.sessions.first();val payroll=app.payroll.paychecks.first()
        val vm=ShiftViewModel(app)
        ui.setContent { ShiftHudTheme { ScheduleScreen(ShiftUiState(loaded=true,now=week.atStartOfDay(ZoneId.systemDefault()).toInstant(),schedule=schedules),vm,false) } }
        listOf(35 to "39h 5m",31 to "39h 25m").forEach { (minutes,total) ->
            ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("EDIT WEEKLY LUNCH PLAN"))
            ui.onNodeWithText("EDIT WEEKLY LUNCH PLAN").performClick()
            ui.onNodeWithText("Saturday–Friday: 2026-10-17 – 2026-10-23").assertExists()
            ui.onNodeWithText("Planned lunch minutes").performScrollTo().performTextReplacement(minutes.toString())
            ui.onNodeWithText("SAVE WEEKLY PLAN").performScrollTo().performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("Weekly Lunch Plan").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Projected paid: $total · Target: 40h 0m"))
            ui.onNodeWithText("Projected paid: $total · Target: 40h 0m").assertExists()
        }
        assertEquals(48,app.preferences.weeklyPlannerSettings.first().weeklyMinutes(week.minusWeeks(1)))
        assertEquals(schedules,app.repository.schedule.first());assertEquals(sessions,app.repository.sessions.first());assertEquals(payroll,app.payroll.paychecks.first())
    }

}
