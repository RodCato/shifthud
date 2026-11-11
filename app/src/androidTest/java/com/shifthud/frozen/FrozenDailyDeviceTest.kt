package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.*
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ui.theme.ShiftHudTheme
import com.shifthud.widget.*
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
    private val dataStore=PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})
    private var scheduledDays=emptySet<LocalDate>()
    private val store=FrozenTaskStore(dataStore,scheduledDates={scheduledDays})
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
    @Test fun sundayLoadsSevenIndependentResponsibilitiesWithoutSetup()=runBlocking {
        val sunday=LocalDate.of(2026,11,1)
        val historical=(mondayFrozenTemplate().take(4)+wednesdayFrozenTemplate(LocalDate.of(2026,10,21)).take(3))
            .map {it.copy(state=FrozenState.CARRY_OVER,workflows=it.workflows+dailyWorkflowId(sunday))}
        dataStore.edit { values ->
            values[stringPreferencesKey("tasks_v1")]=store.encode(historical)
            values[stringSetPreferencesKey("daily_workflow_dates")]=setOf(sunday.toString())
        }
        val vm=FrozenTasksViewModel(store,automatic=true).also{models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
            FrozenDailyScreen(vm,{}, {},currentDate={sunday})
        }}}
        ui.waitUntil(10000) {runBlocking {store.tasks.first().size==14}}
        ui.onNodeWithText("Frozen Workflow").assertIsDisplayed()
        ui.onNodeWithText("CARRYOVERS (0)").assertDoesNotExist()
        ui.onNodeWithText("No weekday template is defined. Saved tasks and outstanding carryovers are shown.").assertDoesNotExist()
        assertEquals(7,store.tasks.first().count{it.carryoverArchived})
        assertEquals(7,dailyFrozenRows(store.tasks.first(),sunday).size)
        historical.forEach {old->assertEquals(old.copy(carryoverArchived=true),store.tasks.first().single{it.id==old.id})}
        capture("sunday")
        val template=sundayFrozenTemplate(sunday)
        template.forEach { task ->
            show(task.title)
            ui.onNodeWithContentDescription("Complete ${task.title}").assertHasClickAction()
        }
        show(template.first().title)
        ui.onNodeWithContentDescription("Complete ${template.first().title}").performClick()
        ui.waitUntil(10000) {runBlocking {store.tasks.first().count{it.state==FrozenState.COMPLETED}==1}}
        assertTrue(store.tasks.first().filter{it.date==sunday && it.id!=template.first().id}.all {it.state==FrozenState.TODO})
        store.openDaily(sunday)
        assertEquals(1,store.tasks.first().count{it.state==FrozenState.COMPLETED})
    }
    @Test fun futureNavigationAndWeekViewNeverPersistProjectedTasks()=runBlocking {
        val today=LocalDate.of(2026,12,31)
        val vm=FrozenTasksViewModel(store,automatic=true).also{models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
            FrozenDailyScreen(vm,{}, {},currentDate={today})
        }}}
        ui.waitUntil(10000) {runBlocking {today in store.dailyDates.first()}}
        val before=store.tasks.first();val dates=store.dailyDates.first()
        ui.onNodeWithContentDescription("Next day").performClick()
        ui.onNodeWithText("Read-only preview · carryovers are projected").assertIsDisplayed()
        ui.onNodeWithText("Add task").assertDoesNotExist()
        assertEquals(before,store.tasks.first());assertEquals(dates,store.dailyDates.first())
        ui.onNodeWithText("Week View").performClick()
        ui.onNodeWithText("2026-12-26 – 2027-01-01 · Saturday–Friday").assertIsDisplayed()
        assertEquals(before,store.tasks.first());assertEquals(dates,store.dailyDates.first())
        capture("week-view")
        show("Fri, Jan 1");ui.onNodeWithText("Fri, Jan 1").performClick()
        ui.onNodeWithText("Read-only preview · carryovers are projected").assertIsDisplayed()
        ui.onNodeWithText("Today").performClick()
        ui.waitUntil(10000) {runBlocking {store.selectedWorkflow.first()==dailyWorkflowId(today)}}
        ui.onNodeWithText("Read-only preview · carryovers are projected").assertDoesNotExist()
        ui.onNodeWithContentDescription("Previous day").performClick()
        ui.waitUntil(10000) {runBlocking {today.minusDays(1) in store.dailyDates.first()}}
        ui.onNodeWithText("Today").performClick()
        ui.waitUntil(10000) {runBlocking {store.selectedWorkflow.first()==dailyWorkflowId(today)}}
        assertFalse(today.plusDays(1) in store.dailyDates.first())
    }
    @Test fun resolveFreightByAnotherAssociateDoesNotCheckItOff()=runBlocking {
        val friday=LocalDate.of(2026,11,6)
        val vm=FrozenTasksViewModel(store,automatic=true).also{models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
            FrozenDailyScreen(vm,{}, {},currentDate={friday})
        }}}
        ui.waitUntil(10000) {runBlocking {store.tasks.first().size==8}}
        val title="Check for unworked Thursday KE freight"
        show(title);ui.onNodeWithText(title).performClick()
        show("Resolved by another associate")
        ui.onNodeWithText("Resolved by another associate").performClick()
        ui.waitUntil(10000) {runBlocking {store.tasks.first().first().resolvedByOtherAt!=null}}
        assertEquals(FrozenState.TODO,store.tasks.first().first().state)
        assertTrue(store.tasks.first().first().history.isEmpty())
        assertEquals(7,dailyFrozenRows(store.tasks.first(),friday).size)
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
        val monday=LocalDate.of(2026,11,9)
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
    @Test fun widgetCompletionSynchronizesWithOpenTasksAndAppReopening()=runBlocking {
        val today=LocalDate.now()
        store.openDaily(today)
        store.add("Widget synchronization fixture","Description","Preserved notes")
        val task=store.tasks.first().single {it.title=="Widget synchronization fixture"}
        val vm=FrozenTasksViewModel(store,automatic=true).also {models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
            FrozenDailyScreen(vm,{}, {},currentDate={today})
        }}}
        ui.waitUntil(10000) {ui.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()}
        val rendered=widgetFrozenState(store.tasks.first(),FloatCounts(),today).tasks.single {it.id==task.id}
        assertTrue(FrozenTaskActionExecutor(store).execute(FrozenTaskCommand(today,rendered.id,rendered.revision,true)))
        ui.waitUntil(10000) {runBlocking {store.tasks.first().single {it.id==task.id}.state==FrozenState.COMPLETED}}
        show("COMPLETED (1)")
        ui.onNodeWithText("COMPLETED (1)").performClick()
        show(task.title)
        ui.onNodeWithContentDescription("Reopen ${task.title}").performClick()
        ui.waitUntil(10000) {runBlocking {!widgetFrozenState(store.tasks.first(),FloatCounts(),today).tasks.single {it.id==task.id}.completed}}
        assertEquals("Preserved notes",store.tasks.first().single {it.id==task.id}.notes)
    }
    @Test fun optionalTrainingAndEndcapGuidanceUseExistingTaskCheckboxes()=runBlocking {
        val monday=LocalDate.of(2026,11,9)
        scheduledDays=setOf(monday)
        val vm=FrozenTasksViewModel(store,automatic=true).also {models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
            FrozenDailyScreen(vm,{}, {},currentDate={monday})
        }}}
        ui.waitUntil(10000) {runBlocking {store.tasks.first().count {it.date==monday}==13}}
        show("Manage Outgoing Endcap Inventory")
        ui.onNodeWithContentDescription("Complete Manage Outgoing Endcap Inventory").assertHasClickAction()
        show("Check Endcap Before Opening Ad Cases")
        ui.onNodeWithContentDescription("Complete Check Endcap Before Opening Ad Cases").assertHasClickAction()
        show("OPTIONAL · LOW PRIORITY (1)")
        ui.onNodeWithText("OPTIONAL · LOW PRIORITY (1)").assertIsDisplayed()
        val title="Check Available CBTs / GRS Training"
        show(title);ui.onNodeWithContentDescription("Complete $title").performClick()
        ui.waitUntil(10000) {runBlocking {widgetFrozenState(store.tasks.first(),FloatCounts(),monday).tasks.single {it.optional}.completed}}
        val saved=store.tasks.first().single {it.optionalTraining}
        assertEquals(1,saved.history.size)
        assertFalse(dailyFrozenRows(store.tasks.first(),monday.plusDays(1)).any {it.task.id==saved.id})
    }
    @Test fun publixProChecksCompleteIndependentlyAndSimsShowsOpportunityLabel()=runBlocking {
        val monday=LocalDate.of(2026,11,16)
        scheduledDays=setOf(monday)
        val vm=FrozenTasksViewModel(store,automatic=true).also {models.put("daily",it)}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
            FrozenDailyScreen(vm,{}, {},currentDate={monday})
        }}}
        ui.waitUntil(10000) {runBlocking {store.tasks.first().any {it.id=="pro-morning-$monday"}}}
        val morning="Check Publix Pro Store Lists"
        val afternoon="Recheck Publix Pro Store Lists"
        show(morning);ui.onNodeWithContentDescription("Complete $morning").performClick()
        ui.waitUntil(10000) {runBlocking {widgetFrozenState(store.tasks.first(),FloatCounts(),monday).tasks.single {it.id=="pro-morning-$monday"}.completed}}
        assertFalse(widgetFrozenState(store.tasks.first(),FloatCounts(),monday).tasks.single {it.id=="pro-afternoon-$monday"}.completed)
        show("Review Pending SIMS Corrections")
        show("When access permits · To do")
        ui.onNodeWithText("When access permits · To do").assertIsDisplayed()
        show(afternoon);ui.onNodeWithContentDescription("Complete $afternoon").performClick()
        ui.waitUntil(10000) {runBlocking {widgetFrozenState(store.tasks.first(),FloatCounts(),monday).tasks.single {it.id=="pro-afternoon-$monday"}.completed}}
        assertFalse(dailyFrozenRows(store.tasks.first(),monday.plusDays(1)).any {it.task.opportunityBased})
    }
}
