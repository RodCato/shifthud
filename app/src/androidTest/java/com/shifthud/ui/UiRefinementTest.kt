package com.shifthud.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import com.shifthud.ui.components.ExpandableSection
import com.shifthud.ui.dashboard.DashboardScreen
import com.shifthud.ui.schedule.ScheduleScreen
import com.shifthud.ui.settings.SettingsScreen
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import java.time.*

/** Read-only UI tests: no click on save/delete-confirm or shift transition actions. */
class UiRefinementTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    private fun vm()=ShiftViewModel(app)
    private val now=Instant.now()
    private fun data(session:WorkSession?=null)=ShiftUiState(session=session,now=now,loaded=true,pay=PayData(loaded=true,sessions=listOfNotNull(session)))
    @Test fun savedExpansionRestoresWithoutChangingPreferences() {
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(ui)
        restoration.setContent { ShiftHudTheme { ExpandableSection("Saved section","Details") { Text("Restored content") } } }
        ui.onNodeWithText("Saved section").performClick()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("Restored content").assertExists()
    }
    @Test fun expansionAndCollapseAreAccessible() {
        ui.setContent { ShiftHudTheme { ExpandableSection("Section","Description") { Text("Content") } } }
        ui.onNodeWithText("Content").assertDoesNotExist()
        ui.onNodeWithText("Section").performClick()
        ui.onNodeWithText("Content").assertIsDisplayed()
        ui.onNodeWithText("Section").performClick()
        ui.onNodeWithText("Content").assertDoesNotExist()
    }
    @Test fun settingsInitiallyCollapsedSearchRevealsTestsAndBackup() {
        ui.setContent { ShiftHudTheme { SettingsScreen(data(),vm(),false) } }
        ui.onNodeWithText("Lunch threshold (minutes)").assertDoesNotExist()
        ui.onNodeWithText("Search settings").performTextInput("Garmin test")
        ui.onNodeWithText("Notification Test Center").assertExists()
        com.shifthud.notification.TestNotificationChannel.entries.forEach { ui.onNodeWithContentDescription("Test ${it.label}").assertExists() }
        ui.onNodeWithText("Search settings").performTextReplacement("backup")
        ui.onNodeWithText("Backup & Restore").performScrollTo().performClick()
        ui.onNodeWithText("CREATE ENCRYPTED BACKUP").assertExists()
    }
    @Test fun settingsSearchExposesHistoricalImportAndPayroll() {
        var historical=false;var payroll=false
        ui.setContent { ShiftHudTheme { SettingsScreen(data(),vm(),false,openHistoricalImport={historical=true},openPaychecks={payroll=true}) } }
        ui.onNodeWithText("Search settings").performTextInput("backfill")
        ui.onNodeWithText("Historical Data Import").performScrollTo().performClick()
        ui.runOnIdle { Assert.assertTrue(historical) }
        ui.onNodeWithText("Search settings").performTextReplacement("paychecks")
        ui.onNodeWithText("Paychecks & screenshot import").performScrollTo().performClick()
        ui.runOnIdle { Assert.assertTrue(payroll) }
    }
    @Test fun workingActionsPrecedeWeeklyDetails() {
        ui.setContent { ShiftHudTheme { DashboardScreen(data(WorkSession(990,clockIn=now.minusSeconds(3600))),vm(),false) } }
        ui.onNodeWithText("CLOCK OUT").assertIsDisplayed()
        ui.onNodeWithText("START LUNCH").assertIsDisplayed()
        ui.onNodeWithText("VIEW WEEK BREAKDOWN").assertDoesNotExist()
    }
    @Test fun onLunchHasEndAndExpectedReturn() {
        ui.setContent { ShiftHudTheme { DashboardScreen(data(WorkSession(990,clockIn=now.minusSeconds(3600),lunchStart=now.minusSeconds(300),state=ShiftState.ON_LUNCH,autoLunchMinutes=60)),vm(),false) } }
        ui.onNodeWithText("END LUNCH").assertIsDisplayed()
        ui.onNodeWithText("Expected return:",substring=true).assertExists()
    }
    @Test fun completedAndOffDutyNavigation() {
        var analytics=false;var paychecks=false
        ui.setContent { ShiftHudTheme { DashboardScreen(data(WorkSession(990,clockIn=now.minusSeconds(3600),clockOut=now.minusSeconds(60),state=ShiftState.COMPLETE)),vm(),false,openAnalytics={analytics=true},openPaychecks={paychecks=true}) } }
        ui.onNodeWithText("SHIFT COMPLETE").assertIsDisplayed()
        ui.onNodeWithText("Weekly breakdown & analytics").performScrollTo().performClick()
        ui.onNodeWithText("WORK ANALYTICS").performScrollTo().performClick()
        ui.onNodeWithText("PAYCHECKS & SCREENSHOT IMPORT").performScrollTo().performClick()
        ui.runOnIdle { Assert.assertTrue(analytics && paychecks) }
    }
    @Test fun offDutyExposesClockInAndPlanner() {
        ui.setContent { ShiftHudTheme { DashboardScreen(data(),vm(),false) } }
        ui.onNodeWithText("CLOCK IN — UNSCHEDULED").assertIsDisplayed()
        ui.onNodeWithText("Edit Lunch Plan").performScrollTo().performClick()
        ui.onNodeWithText("Planned lunch minutes").assertExists()
    }
    @Test fun scheduleExpansionEditAndDeleteConfirmation() {
        val today=LocalDate.now()
        val s=ScheduledShift(990,today,LocalTime.of(4,0),LocalTime.of(13,0),60)
        ui.setContent { ShiftHudTheme { ScheduleScreen(data().copy(schedule=listOf(s)),vm(),false) } }
        ui.onNodeWithText("Edit shift").assertDoesNotExist()
        ui.onNodeWithText("Scheduled work",substring=true).performScrollTo().performClick()
        ui.onNodeWithText("Edit shift").performScrollTo().performClick()
        ui.onNodeWithText("Override lunch minutes (blank = default)").assertDoesNotExist()
        ui.onNodeWithText("Note (optional)").assertExists()
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Delete shift").performScrollTo().performClick()
        ui.onNodeWithText("Delete scheduled shift?").assertExists()
        ui.onNodeWithText("Cancel").performClick()
    }
}
