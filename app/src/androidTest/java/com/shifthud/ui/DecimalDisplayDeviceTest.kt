package com.shifthud.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.*
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import com.shifthud.ui.dashboard.TimeRecordDialog
import com.shifthud.ui.time.LocalDecimalHours
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

class DecimalDisplayDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun historyDualDisplayToggleLargeFontsAndAccessibility() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
        val start=Instant.parse("2026-10-17T23:00:00Z")
        val record=WorkSession(999901,clockIn=start,clockOut=start.plusSeconds(8*3600+270),state=ShiftState.COMPLETE)
        var enabled by mutableStateOf(true)
        val vm=ShiftViewModel(app)
        ui.setContent {
            CompositionLocalProvider(LocalDecimalHours provides enabled) {
                ShiftHudTheme { TimeRecordDialog(record,vm,false,record.clockOut!!,{}) }
            }
        }
        ui.onNodeWithText("8.08 decimal hours").assertExists()
        ui.runOnIdle { enabled=false }
        ui.onNodeWithText("8.08 decimal hours").assertDoesNotExist()
        ui.onNodeWithText("Paid 8h 4m",substring=true).assertExists()
        ui.runOnIdle { enabled=true }
        // Dialogs have their own Android window: use the actual system font configuration.
        val font=app.resources.configuration.fontScale
        run {
            ui.waitUntil(5000) { ui.onAllNodesWithText("8.08 decimal hours").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("8.08 decimal hours").performScrollTo().assertIsDisplayed()
            val layouts=mutableListOf<TextLayoutResult>()
            ui.onNodeWithText("8.08 decimal hours").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.none { it.hasVisualOverflow })
            assertEquals(font,layouts.single().layoutInput.density.fontScale,0.01f)
            InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let { assertEquals(it,font,0.01f) }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            java.io.File(instrumentation.targetContext.cacheDir,"decimal-history-${font.toInt()}x.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
    }
    @Test fun settingsSwitchChangesOnlyDisplayPreference() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
        val key=booleanPreferencesKey("show_decimal_hours")
        val original=runBlocking { app.preferences.store.data.first()[key] }
        val before=original ?: true
        try {
            val vm=ShiftViewModel(app)
            ui.setContent { ShiftHudTheme { com.shifthud.ui.settings.SettingsScreen(ShiftUiState(loaded=true),vm,false) } }
            ui.onNodeWithText("Search settings").performTextInput("decimal")
            ui.onNodeWithText("Show decimal hours").performScrollTo().performClick()
            ui.waitUntil(5000) { runBlocking { app.preferences.showDecimalHours.first() } != before }
            ui.onNodeWithText("Show decimal hours").assertIsToggleable()
            assertEquals(!before,runBlocking { app.preferences.showDecimalHours.first() })
        } finally {
            runBlocking { app.preferences.store.edit { if(original==null) it.remove(key) else it[key]=original } }
        }
    }
    @Test fun preferenceSurvivesProcessRestart() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val phase=InstrumentationRegistry.getArguments().getString("decimalPhase") ?: return@runBlocking
        val app=instrumentation.targetContext.applicationContext as ShiftHudApplication
        val key=booleanPreferencesKey("show_decimal_hours")
        val saved=java.io.File(app.cacheDir,"decimal-test-original.txt")
        if(phase=="prepare") {
            check(!saved.exists())
            saved.writeText(app.preferences.store.data.first()[key]?.toString() ?: "unset")
            app.preferences.setShowDecimalHours(false)
            assertFalse(app.preferences.showDecimalHours.first())
        } else {
            try {
                assertFalse(app.preferences.showDecimalHours.first())
                app.preferences.setShowDecimalHours(true)
                assertTrue(app.preferences.showDecimalHours.first())
            } finally {
                app.preferences.store.edit { if(saved.readText()=="unset") it.remove(key) else it[key]=saved.readText().toBooleanStrict() }
                saved.delete()
            }
        }
    }
}
