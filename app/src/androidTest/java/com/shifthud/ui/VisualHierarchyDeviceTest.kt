package com.shifthud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.TestNotificationChannel
import com.shifthud.ui.components.Metric
import com.shifthud.ui.settings.SettingsScreen
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class VisualHierarchyDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    @Test fun metricsHaveDistinctTypeScaleAndReadableOrderAtLargeFont() {
        ui.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                ShiftHudTheme { Column(Modifier.widthIn(max=320.dp).verticalScroll(rememberScrollState())) {
                    Metric("Actual worked hours","16h 7m","actual",true,"of 40h target")
                    Metric("Projected weekly total","40h 19m","projected")
                } }
            }
        }
        ui.onNodeWithTag("actual").assertTextEquals("Actual worked hours","16h 7m","of 40h target")
        fun layout(text:String):TextLayoutResult {
            val results=mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(results)}
            return results.single()
        }
        val main=layout("16h 7m");val secondary=layout("40h 19m");val label=layout("Actual worked hours")
        assertTrue(main.layoutInput.style.fontSize>secondary.layoutInput.style.fontSize)
        assertTrue(secondary.layoutInput.style.fontSize>label.layoutInput.style.fontSize)
        assertFalse("Primary overflow: size=${main.size}, lines=${main.lineCount}, width=${main.didOverflowWidth}, height=${main.didOverflowHeight}",main.hasVisualOverflow)
        assertFalse("Secondary overflow: size=${secondary.size}, lines=${secondary.lineCount}, width=${secondary.didOverflowWidth}, height=${secondary.didOverflowHeight}",secondary.hasVisualOverflow)
    }
    @Test fun searchableDiagnosticsStartsCollapsedAndRestoresDetailsWithoutDataChanges()=runBlocking {
        val sessions=app.repository.sessions.first();val shifts=app.repository.schedule.first()
        val payroll=app.payroll.paychecks.first();val preferences=app.preferences.store.data.first()
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(ui)
        val vm=ShiftViewModel(app)
        restoration.setContent { ShiftHudTheme { SettingsScreen(ShiftUiState(loaded=true),vm,false) } }
        ui.onNodeWithText("Search settings").performTextInput("test diagnostics")
        ui.onNodeWithText("Notification Diagnostics").assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
        ui.onNodeWithContentDescription("Test Minimum lunch alert").assertDoesNotExist()
        ui.onNodeWithText("Notification Diagnostics").performScrollTo().performClick()
        TestNotificationChannel.entries.forEach { channel ->
            ui.onNodeWithContentDescription("Test ${channel.label}").performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
        ui.onNodeWithText("Permission:",substring=true).assertDoesNotExist()
        ui.onNodeWithContentDescription("Minimum lunch alert details").performScrollTo().performClick()
        ui.onNodeWithText("Permission:",substring=true).assertExists()
        ui.onNodeWithText("Channel importance:",substring=true).assertExists()
        ui.onNodeWithText("Sound:",substring=true).assertExists()
        ui.onNodeWithText("Vibration:",substring=true).assertExists()
        ui.onNodeWithText("ANDROID NOTIFICATION SETTINGS").assertHasClickAction()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithContentDescription("Minimum lunch alert details").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        assertEquals(sessions,app.repository.sessions.first());assertEquals(shifts,app.repository.schedule.first())
        assertEquals(payroll,app.payroll.paychecks.first());assertEquals(preferences,app.preferences.store.data.first())
    }
    @Test fun allDiagnosticButtonsInvokeExistingAndroidTestActions()=runBlocking {
        val manager=app.getSystemService(android.app.NotificationManager::class.java)
        val before=app.repository.sessions.first();val preferences=app.preferences.store.data.first()
        val vm=ShiftViewModel(app)
        ui.setContent { ShiftHudTheme { SettingsScreen(ShiftUiState(loaded=true),vm,false) } }
        ui.onNodeWithText("Search settings").performTextInput("test diagnostics")
        ui.onNodeWithText("Notification Diagnostics").performScrollTo().performClick()
        try {
            TestNotificationChannel.entries.forEach { channel ->
                // The emulator acceptance run grants permission; preserve Android's normal cooldown.
                kotlinx.coroutines.delay(3100)
                ui.onNodeWithContentDescription("Test ${channel.label}").performScrollTo().performClick()
                val blocked=app.notifications.testCenter.status(channel).blockedReason
                if(blocked==null) ui.waitUntil(5000) { manager.activeNotifications.any { it.id==channel.testId && it.notification.channelId==channel.channelId } }
                else ui.onAllNodesWithText(blocked).onFirst().assertExists()
                manager.cancel(channel.testId)
            }
            assertEquals(before,app.repository.sessions.first());assertEquals(preferences,app.preferences.store.data.first())
        } finally { TestNotificationChannel.entries.forEach { manager.cancel(it.testId) } }
    }
}
