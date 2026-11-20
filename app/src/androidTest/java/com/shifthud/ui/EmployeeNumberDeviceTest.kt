package com.shifthud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.data.preferences.EmployeeNumberPreferences
import com.shifthud.ui.settings.EmployeeNumberField
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class EmployeeNumberDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val file=File(context.cacheDir,"employee-isolated.preferences_pb")
    private val job=SupervisorJob()
    private var refreshes=0
    private val preferences=EmployeeNumberPreferences(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})) {refreshes++}
    @After fun close()=runBlocking {job.cancelAndJoin();file.delete();Unit}
    @Test fun settingsFieldSavesEditsAndClearsWithoutLosingLeadingZeros() {
        ui.setContent {ShiftHudTheme {Surface(Modifier.fillMaxSize().systemBarsPadding()) {Column {
            val saved by preferences.value.collectAsState(initial=null)
            EmployeeNumberField(saved,preferences::set)
        }}}}
        ui.waitUntil(5000) {ui.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        ui.onNode(hasSetTextAction()).performTextInput(" 0012345 ")
        ui.onNodeWithText("Save employee number").performClick()
        ui.waitUntil(5000) {runBlocking {preferences.value.first()=="0012345"}}
        ui.onNode(hasSetTextAction()).performTextReplacement("00009")
        ui.onNodeWithText("Save employee number").performClick()
        ui.waitUntil(5000) {runBlocking {preferences.value.first()=="00009"}}
        ui.onNodeWithText("Clear").performClick()
        ui.onNodeWithText("Save employee number").performClick()
        ui.waitUntil(5000) {runBlocking {preferences.value.first().isEmpty()}}
        assertEquals(3,refreshes)
    }
}
