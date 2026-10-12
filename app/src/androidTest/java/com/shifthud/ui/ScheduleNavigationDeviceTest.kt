package com.shifthud.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import com.shifthud.ui.schedule.*
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class ScheduleNavigationDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    private val saturday=LocalDate.of(2026,10,10)
    private fun state()=ShiftUiState(now=saturday.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant(),loaded=true)
    @Test fun navigationSurvivesRecompositionAndSavedStateWithoutShowingOtherWeeks() {
        val future=ScheduledShift(991,saturday.plusWeeks(1),LocalTime.of(16,0),LocalTime.of(23,0),60)
        var data by mutableStateOf(state().copy(schedule=listOf(future)))
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(ui)
        val vm=ShiftViewModel(app)
        restoration.setContent { ShiftHudTheme { ScheduleScreen(data,vm,false) } }
        ui.onNodeWithText("4:00 PM – 11:00 PM",substring=true).assertDoesNotExist()
        ui.onNodeWithContentDescription("Next week").assertHasClickAction().performClick()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("4:00 PM – 11:00 PM",substring=true))
        ui.onNodeWithText("4:00 PM – 11:00 PM",substring=true).assertExists()
        ui.runOnIdle { data=data.copy(now=data.now.plusSeconds(8*86400)) }
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("schedule-list").performScrollToIndex(0)
        ui.onNodeWithTag("selected-schedule-week").assertTextEquals(scheduleWeek(future.date).scheduleLabel(Locale.getDefault()))
        ui.onNodeWithContentDescription("Previous week").performClick()
        ui.onNodeWithText("4:00 PM – 11:00 PM",substring=true).assertDoesNotExist()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("No scheduled shifts in this week."))
        ui.onNodeWithText("No scheduled shifts in this week.").assertExists()
    }
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun keyboardCanNavigateWeeksAndImportHasAccessibleAction() {
        val vm=ShiftViewModel(app)
        lateinit var inputMode: androidx.compose.ui.input.InputModeManager
        ui.setContent {
            inputMode=androidx.compose.ui.platform.LocalInputModeManager.current
            ShiftHudTheme { ScheduleScreen(state(),vm,false) }
        }
        // Compose key injection does not itself switch Android out of touch mode.
        ui.runOnIdle { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        ui.onNodeWithContentDescription("Previous week").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithContentDescription("Previous week").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
        ui.onNodeWithContentDescription("Next week").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        ui.onNodeWithTag("selected-schedule-week").assertTextEquals(scheduleWeek(saturday.plusWeeks(1)).scheduleLabel(Locale.getDefault()))
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Import Schedule Screenshot"))
        ui.onNodeWithText("Import Schedule Screenshot").assertHasClickAction()
    }
    @Test fun legacyDetailsAreHiddenUntilAdvancedAndEditingStillWorks() {
        val s=ScheduledShift(990,saturday,LocalTime.of(4,0),LocalTime.of(13,0),45)
        val vm=ShiftViewModel(app)
        ui.setContent { ShiftHudTheme { ScheduleScreen(state().copy(schedule=listOf(s)),vm,false) } }
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Scheduled work"))
        ui.onNodeWithText("Scheduled work").performClick()
        ui.onNodeWithText("Legacy lunch value:",substring=true).assertDoesNotExist()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Edit shift"))
        ui.onNodeWithText("Edit shift").performClick()
        ui.onNodeWithText("Note (optional)").assertExists()
        ui.onNodeWithText("Override lunch minutes",substring=true).assertDoesNotExist()
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Advanced schedule details"))
        ui.onNodeWithText("Advanced schedule details").performClick()
        ui.onNodeWithTag("schedule-list").performScrollToNode(hasText("Legacy lunch value: 45 minutes · Retained for reference"))
        ui.onNodeWithText("Legacy lunch value: 45 minutes · Retained for reference").assertExists()
    }
}
