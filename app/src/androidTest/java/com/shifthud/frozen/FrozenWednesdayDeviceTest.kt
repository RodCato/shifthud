package com.shifthud.frozen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*

class FrozenWednesdayDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val date=LocalDate.of(2026,10,21)
    private val job=SupervisorJob()
    private val models=ViewModelStore()
    private val file=File(context.cacheDir,"d4-device-test.preferences_pb")
    private val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
    @After fun cleanup()=runBlocking { ui.runOnIdle { models.clear() };job.cancelAndJoin();file.delete();Unit }
    private fun show(text:String) { ui.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
    private fun click(text:String) { show(text);ui.onNodeWithText(text).performClick() }
    private fun capture(name:String) {
        ui.waitForIdle()
        ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().filter { it.boundsInRoot.width>0 && it.boundsInRoot.height>0 }.forEach { node ->
            node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let { action ->
                val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                assertTrue("Text overflow: ${layouts.filter { it.hasVisualOverflow }.map { it.layoutInput.text }}",layouts.none { it.hasVisualOverflow })
            }
        }
        File(context.cacheDir,"frozen-d4-$name.png").outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun explicitStartArrivalUndoAndSavedDateNavigation()=runBlocking {
        store.initialize();store.startTuesday(date.minusDays(1));store.selectWorkflow("")
        ui.setContent { ShiftHudTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            val vm=remember { FrozenTasksViewModel(store).also { models.put("frozen",it) } }
            FrozenTasksScreen(vm) {}
        } } }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Start Wednesday Frozen Workflow").fetchSemanticsNodes().isNotEmpty() }
        click("Start Wednesday Frozen Workflow")
        ui.onNodeWithText("Start / reopen").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Wednesday — Frozen Truck").fetchSemanticsNodes().isNotEmpty() }
        val assignment=store.truckWorkflows.first().single().date
        val before=store.tasks.first()
        click("TRUCK ARRIVED")
        ui.waitUntil(10000) { runBlocking { store.truckWorkflows.first().single().arrival!=null } }
        assertEquals(before,store.tasks.first())
        click("Correct arrival / mark not arrived")
        ui.onNodeWithText("Mark not arrived").performClick()
        ui.waitUntil(10000) { runBlocking { store.truckWorkflows.first().single().arrival==null } }
        assertEquals(2,store.truckWorkflows.first().single().events.size)
        click("Choose saved workflow / Monday history")
        ui.onNodeWithText("Tuesday workflow · ${date.minusDays(1)}").performClick()
        click("Choose saved workflow / Monday history")
        ui.onNodeWithText("Wednesday — Frozen Truck · $assignment").performClick()
        assertEquals(before,store.tasks.first())
        show("TRUCK: NOT ARRIVED");capture("waiting")
    }
    @Test fun narrowLargeFontCarryoverAndUnrestrictedPhasesWithArrivalProminence() {
        var scale by mutableStateOf(1f)
        var tasks by mutableStateOf(wednesdayFrozenTemplate(date)+mondayFrozenTemplate().takeLast(3).take(2).map { it.copy(state=FrozenState.CARRY_OVER,notes="Synthetic handoff",workflows=listOf(wednesdayWorkflowId(date))) })
        var truck by mutableStateOf(FrozenTruckWorkflow(date,tasks.map { it.id }))
        ui.setContent { ShiftHudTheme { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) { Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                FrozenTasksContent(tasks,false,null,{}, {},{id,state->tasks=tasks.map { if(it.id==id)it.copy(state=state) else it }},{_,_,_,_,_->},
                    workflow=wednesdayWorkflowId(date),truck=truck,truckArrival={arrived->truck=truck.copy(events=truck.events+TruckArrivalEvent(arrived,Instant.parse("2026-10-21T18:17:00Z"),"America/Chicago"))})
            } }
        } }
        show("CARRYOVERS (2)")
        ui.onNodeWithContentDescription("Complete Review MASC").performClick()
        assertEquals(FROZEN_MONDAY,tasks.first { it.title=="Review MASC" }.date)
        show("Perform Random Counts")
        ui.onNodeWithContentDescription("Complete Perform Random Counts").performClick()
        assertNull(truck.arrival) // After-truck work is available while waiting.
        click("TRUCK RECEIVING (4)") // Collapse before arrival; arrival expands it again.
        click("TRUCK ARRIVED")
        show("TRUCK RECEIVING (4)")
        ui.onNodeWithText("TRUCK RECEIVING (4)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        capture("receiving")
        assertEquals(2,tasks.count { it.state==FrozenState.COMPLETED })
        ui.runOnIdle { scale=2f }
        show("TRUCK RECEIVING (4)");capture("large")
        show("COMPLETED (2)")
        ui.onNodeWithText("COMPLETED (2)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
        click("COMPLETED (2)");show("Review MASC")
        ui.onNodeWithContentDescription("Reopen Review MASC").performClick()
        assertEquals("Synthetic handoff",tasks.first { it.title=="Review MASC" }.notes)
        show("BEFORE TRUCK (7)")
    }
}
