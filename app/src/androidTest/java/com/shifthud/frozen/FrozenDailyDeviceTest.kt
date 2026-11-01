package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

class FrozenDailyDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val models=androidx.lifecycle.ViewModelStore()
    private val job=SupervisorJob()
    private val file=File(context.cacheDir,"daily-frozen-isolated.preferences_pb")
    private val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
    @After fun close()=runBlocking {ui.runOnIdle {models.clear()};job.cancelAndJoin();file.delete();Unit}
    private fun show(text:String) {ui.onNode(hasScrollAction()).performScrollToNode(hasText(text))}
    private fun capture(name:String) {
        ui.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(250)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(context.cacheDir,"daily-$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
    }
    @Test fun todayLoadsWithoutStartAndCheckboxPersists() {
        val today=LocalDate.of(2026,11,2)
        val vm=FrozenTasksViewModel(store,automatic=true).also{models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {FrozenDailyScreen(vm,{}, {},currentDate={today})}}}
        ui.waitUntil(10000) {runBlocking {today in store.dailyDates.first()}}
        ui.onNodeWithText("Frozen Workflow").assertIsDisplayed();capture("today")
        assertEquals(10,runBlocking {store.tasks.first().size})
        ui.onNodeWithText("Start Tuesday Frozen Workflow").assertDoesNotExist()
        ui.onNodeWithText("Start Wednesday Frozen Workflow").assertDoesNotExist()
        show("Add task");ui.onNodeWithText("Add task").performClick()
        ui.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Synthetic daily task")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save task").performClick()
        ui.waitUntil(10000) {runBlocking {store.tasks.first().any{it.title=="Synthetic daily task"}}}
        show("Synthetic daily task")
        ui.onNodeWithContentDescription("Complete Synthetic daily task").performClick()
        ui.waitUntil(10000) {runBlocking {store.tasks.first().any{it.title=="Synthetic daily task" && it.state==FrozenState.COMPLETED}}}
        assertTrue(runBlocking {store.tasks.first()}.all{it.date==today})
    }
    @Test fun midnightChangesChecklistWithoutResettingCompletedSources()=runBlocking {
        val monday=LocalDate.of(2026,11,2)
        var date=monday
        store.openDaily(monday)
        val first=store.tasks.first().first()
        store.setDailyState(monday,first.id,FrozenState.COMPLETED)
        val vm=FrozenTasksViewModel(store,automatic=true).also{models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {FrozenDailyScreen(vm,{}, {},currentDate={date})}}}
        ui.waitUntil(10000) {runBlocking {store.selectedWorkflow.first()==dailyWorkflowId(monday)}}
        ui.runOnIdle {date=monday.plusDays(1)}
        ui.waitUntil(10000) {runBlocking {monday.plusDays(1) in store.dailyDates.first()}}
        assertEquals(FrozenState.COMPLETED,store.tasks.first().single{it.id==first.id}.state)
        assertFalse(dailyFrozenRows(store.tasks.first(),date).any{row->row.sources.any{it.id==first.id}})
    }
    @Test fun mergedCarryoverShowsDatesAndSourcesAtLargeFonts()=runBlocking {
        val monday=LocalDate.of(2026,11,2)
        store.openDaily(monday.minusDays(6));store.openDaily(monday)
        val rows=dailyFrozenRows(store.tasks.first(),monday)
        ui.setContent {ShiftHudTheme {val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,2f)) {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                FrozenTasksContent(rows.map{it.task},false,null,{}, {},{_,_->},{_,_,_,_,_->},workflow=dailyWorkflowId(monday),dailyDate=monday,sources=rows.associate{it.task.id to it.sources})
            }}
        }}
        show("Run Frozen backstock")
        ui.onAllNodesWithText("Run Frozen backstock").assertCountEquals(1)
        ui.onNodeWithContentDescription("Complete Run Frozen backstock").assertHasClickAction();capture("large-carryover")
    }
}
