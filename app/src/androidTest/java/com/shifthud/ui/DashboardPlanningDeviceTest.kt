package com.shifthud.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.workWeekFor
import com.shifthud.ui.dashboard.WeeklyTargetSection
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import java.time.*

class DashboardPlanningDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    @Test fun compactSummaryShowsCountdownAndRestoresAccessibleDetails() {
        val start=workWeekFor(LocalDate.now()).start.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val out=start.plusSeconds(8*3600+4*60+30)
        val record=WorkSession(987,clockIn=start,clockOut=out,state=ShiftState.COMPLETE)
        val vm=ShiftViewModel(app)
        val restore=androidx.compose.ui.test.junit4.StateRestorationTester(ui)
        restore.setContent { ShiftHudTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            WeeklyTargetSection(ShiftUiState(now=out,loaded=true,pay=PayData(loaded=true,sessions=listOf(record))),vm,false)
        } } }
        ui.onNodeWithTag("weekly-actual").assertTextEquals("Actual worked hours","8h 4m","of 40h 0m target")
        ui.onNodeWithText("Remaining: 31h 56m").assertExists()
        ui.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo((8*3600+4*60+30)/144000f,0f..1f))
        ui.onNodeWithText("Projected shortfall: 31h 56m").assertExists()
        ui.onNodeWithText("Edit Lunch Plan").assertDoesNotExist()
        ui.onNodeWithText("Employer hours available",substring=true).assertDoesNotExist()
        ui.onNodeWithText("View Planning Details").performScrollTo().assertHasClickAction().performClick()
        ui.onNodeWithText("Employer hours available for 0 of 0 saved shifts.").assertExists()
        restore.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("Employer hours available for 0 of 0 saved shifts.").assertExists()
        ui.onNodeWithText("View Planning Details").performScrollTo().performClick()
        ui.onNodeWithText("Employer hours available",substring=true).assertDoesNotExist()
    }
    @Test fun excessAndFinalDayRecommendationRemainVisibleWithoutLunchEditor() {
        val week=workWeekFor(LocalDate.now()).start
        val rows=(2L..6L).map { ScheduledShift(it,week.plusDays(it),LocalTime.of(8,0),LocalTime.of(17,if(it==6L)20 else 0)) }
        val vm=ShiftViewModel(app)
        ui.setContent { ShiftHudTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            WeeklyTargetSection(ShiftUiState(schedule=rows,now=week.atStartOfDay(ZoneId.systemDefault()).toInstant(),loaded=true,pay=PayData(loaded=true)),vm,false)
        } } }
        ui.onNodeWithTag("weekly-projected").assertTextEquals("Projected weekly total","40h 20m")
        ui.onNodeWithText("Projected excess: 20m").assertExists()
        ui.onNodeWithText("Estimated final-day clock-out:",substring=true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Edit Lunch Plan").assertDoesNotExist()
    }
}
