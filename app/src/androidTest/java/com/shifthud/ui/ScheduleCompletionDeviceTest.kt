package com.shifthud.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.data.local.entity.entity
import com.shifthud.domain.model.*
import com.shifthud.ui.schedule.*
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.time.*

class ScheduleCompletionDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    private val date=LocalDate.of(2026,10,10)
    private val shift=ScheduledShift(996401,date,LocalTime.of(8,0),LocalTime.of(17,0))
    private val session=WorkSession(996401,shift.id,Instant.parse("2026-10-10T08:00:00Z"))
    @After fun cleanup()=runBlocking {
        // Remove only this test's fixture, including an unfinished session if an assertion fails.
        app.database.shifts().update(session.copy(clockOut=session.clockIn.plusSeconds(3600),state=ShiftState.COMPLETE).entity())
        app.database.shifts().deleteHistorical(session.id)
        app.database.shifts().delete(shift.id)
    }
    @Test fun linkedStatusAndSummaryRefreshAtLargeFontWithAccessibleReadOnlyIndicator() {
        runBlocking { app.database.shifts().save(shift.entity()) }
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(ui)
        val vm=ShiftViewModel(app)
        restoration.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                ShiftHudTheme { ScheduleScreen(ShiftUiState(schedule=listOf(shift),now=session.clockIn,loaded=true),vm,false) }
            }
        }
        fun summary(text:String) {
            ui.onNodeWithTag("schedule-list").performScrollToIndex(0)
            ui.waitUntil(5000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("schedule-completion-summary").assertTextEquals(text)
        }
        fun row(text:String) {
            ui.onNodeWithTag("schedule-list").performScrollToNode(hasText(text))
            ui.waitUntil(5000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            // TalkBack receives the status on a single expansion button, never a toggleable checkbox.
            ui.onNodeWithText(text).assertIsDisplayed().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        }
        summary("0 of 1 shifts completed");row("Scheduled")
        runBlocking { app.database.shifts().insert(session.entity()) }
        ui.waitForIdle();row("Working")
        runBlocking { app.database.shifts().update(session.copy(lunchStart=session.clockIn.plusSeconds(3600),state=ShiftState.ON_LUNCH).entity()) }
        ui.waitForIdle();row("On lunch")
        val ended=session.copy(clockOut=session.clockIn.plusSeconds(7200),state=ShiftState.COMPLETE)
        runBlocking { app.database.shifts().update(ended.entity()) }
        summary("1 of 1 shifts completed");row("Completed")
        ui.onNodeWithText("Completed").performClick()
        restoration.emulateSavedInstanceStateRestore()
        row("Completed")
        ui.onNodeWithText("Completed").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        runBlocking { app.database.shifts().deleteHistorical(session.id) }
        summary("0 of 1 shifts completed");row("Scheduled")
    }
}
