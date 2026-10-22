package com.shifthud.frozen.count

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.frozen.*
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class CountRouteDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val job=SupervisorJob();private val models=ViewModelStore()
    private val file=File(context.cacheDir,"f1-device-test.preferences_pb")
    private val frozenFile=File(context.cacheDir,"f1-frozen-regression.preferences_pb")
    private val store=CountRouteStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
    @After fun cleanup()=runBlocking {ui.runOnIdle {models.clear()};job.cancelAndJoin();file.delete();frozenFile.delete();Unit}
    private fun show(text:String) {ui.onNode(hasScrollAction()).performScrollToNode(hasText(text))}
    private fun click(text:String) {show(text);ui.onNodeWithText(text).performClick()}
    private fun data()=runBlocking {store.data.first()}
    private fun waitFor(condition:(CountRouteData)->Boolean)=ui.waitUntil(10000) {condition(data())}
    private fun screen(scale:Float=1f) {
        ui.setContent {ShiftHudTheme {val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                val vm=remember {CountRouteViewModel(store).also {models.put("count",it)}}
                CountRouteScreen(vm) {}
            }}
        }}
        ui.waitUntil(10000) {(models.get("count") as? CountRouteViewModel)?.data?.value!=null}
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        // UI automation captures the compositor, which may lag Compose semantics by a frame.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(context.cacheDir,"count-route-$name.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {screenshot.recycle()}
        ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().filter {it.boundsInRoot.width>0 && it.boundsInRoot.height>0}.forEach {node->
            node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let {action->val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                assertTrue("Text overflow: ${layouts.filter {it.hasVisualOverflow}.map {"${it.layoutInput.text} ${it.size} ${it.layoutInput.constraints} width=${it.didOverflowWidth} height=${it.didOverflowHeight}"}}",layouts.none {it.hasVisualOverflow})}
        }
    }
    @Test fun emptySetupPartialSaveAndStartThroughUi() {
        screen();show("Door Map Setup");capture("empty")
        click("Door Map Setup")
        listOf("Aisle 10 — Right" to "2","Aisle 10 — Left" to "0","Aisle 11 — Right" to "1","Aisle 12 — Frozen" to "3").forEach {(label,value)->
            show(label);ui.onNodeWithText(label).performTextInput(value)
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(5)
        ui.onNodeWithText("SAVE DOOR COUNTS").assertIsDisplayed();capture("map")
        click("SAVE DOOR COUNTS");waitFor {it.map.first().count==2}
        assertEquals(listOf(2,0,1,null,3),data().map.map {it.count})
        click("Start New Counting Cycle");waitFor {it.active!=null}
        assertEquals(listOf("A10-R-01","A10-R-02","A11-R-01","A12-F-01","A12-F-02","A12-F-03"),data().active!!.doors.map {it.id})
        assertFalse(data().active!!.mapComplete);show("+1 Count");capture("active")
        show("Complete Door / Next Door");ui.onNodeWithText("Complete Door / Next Door").performTouchInput {doubleClick()}
        waitFor {it.active!!.sampled==1};assertEquals("A10-R-02",data().active!!.current)
    }
    @Test fun countPauseSkipRevisitFinishAndManualApproval()=runBlocking {
        store.saveMap(0,simpleDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=2) else s})
        store.start(1);screen()
        repeat(3) {n->
            if(n==0) {show("+1 Count");ui.onNodeWithText("+1 Count").performTouchInput {doubleClick()}} else click("+1 Count")
            waitFor {it.active!!.products==n+1};android.os.SystemClock.sleep(360)
            assertEquals(n+1,data().active!!.products)
        }
        click("Undo");waitFor {it.active!!.products==2}
        click("+1 Count");waitFor {it.active!!.products==3}
        click("Pause");waitFor {it.active!!.paused}
        show("Continue Aisle 10 · Right · Door 1 — 3/5");capture("paused")
        click("Resume");waitFor {!it.active!!.paused}
        assertEquals("A10-R-01",data().active!!.current)
        click("Skip Location");ui.onNodeWithText("Location note (optional)").performTextInput("Synthetic blocked access")
        androidx.test.espresso.Espresso.closeSoftKeyboard();ui.onNodeWithText("Mark skipped").performClick();waitFor {it.active!!.skipped==1}
        click("Complete Door / Next Door");waitFor {it.active!!.sampled==1}
        click("Aisle 10 · Right");click("Revisit A10-R-01");waitFor {it.active!!.current=="A10-R-01"}
        assertEquals(3,data().active!!.currentDoor!!.products)
        click("Complete Door / Next Door");waitFor {it.active!!.sampled==2}
        click("Sessions & manual approval")
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Edit manual status",substring=true))
        ui.onAllNodes(hasText("Edit manual status",substring=true)).onFirst().performClick()
        ui.onNodeWithContentDescription("Awaiting management approval").performClick()
        ui.onNodeWithText("Save manual status").performClick();waitFor {it.active!!.sessions.any {s->s.submission==Submission.AWAITING}}
        click("Finish Counting Cycle");ui.onNodeWithText("Finish and keep history").performClick();waitFor {it.active==null}
        assertEquals(3,data().cycles.single().products);assertFalse(data().cycles.single().covered)
        click("Cycle 1");capture("history")
    }
    @Test fun narrowLargeTextAndMapCorrectionsPreserveActiveDoor()=runBlocking {
        store.saveMap(0,simpleDoorMap().mapIndexed {i,s->s.copy(count=if(i==0)2 else 0)})
        store.start(1);store.count(2,1,"A10-R-01")
        screen(2f);show("+1 Count");capture("large")
        click("Door Map Setup")
        show("Aisle 10 — Right")
        ui.onNodeWithText("Aisle 10 — Right").performTextClearance()
        ui.onNodeWithText("Aisle 10 — Right").performTextInput("1")
        androidx.test.espresso.Espresso.closeSoftKeyboard();show("SAVE DOOR COUNTS");capture("large-map")
        click("SAVE DOOR COUNTS");waitFor {it.map.first().count==1}
        assertEquals(2,data().active!!.doors.size);assertEquals(1,data().active!!.products);assertEquals("A10-R-01",data().active!!.current)
    }
    @Test fun ambiguousOldCountsRequireConfirmationAndKeepOriginalMap()=runBlocking {
        val old=initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=2) else s.copy(excluded=true)}
        store.saveMap(0,old);screen();click("Door Map Setup")
        show("Aisle 10 — Right");ui.onNodeWithText("Aisle 10 — Right").performTextInput("2")
        androidx.test.espresso.Espresso.closeSoftKeyboard();click("SAVE DOOR COUNTS")
        ui.onNodeWithText("Confirm Frozen door counts").assertIsDisplayed();ui.onNodeWithText("Cancel").performClick()
        assertEquals(old,data().map)
        click("SAVE DOOR COUNTS");ui.onNodeWithText("Confirm counts").performClick();waitFor {isSimpleMap(it.map)}
        assertEquals(old,data().previousMaps.single());assertEquals(2,data().map.first().count)
    }

    @Test fun frozenEntryAndReturnPreserveChecklistAndTruckState()=runBlocking {
        val frozen=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={frozenFile}))
        frozen.initialize();frozen.startWednesday(java.time.LocalDate.of(2026,10,21));frozen.setTruckArrived(java.time.LocalDate.of(2026,10,21),true)
        val varieties=listOf(EndcapProduct("Synthetic vanilla","12345","Desserts","Desserts — Right"),EndcapProduct("Synthetic mango","12346","Desserts","Desserts — Right"))
        frozen.importProducts(java.time.LocalDate.of(2026,10,20),java.time.LocalDate.of(2026,10,17),TARGET_ENDCAP,varieties)
        val family=frozen.tasks.first().single {it.family.isNotBlank()}
        frozen.chooseEndcapVariety(family.id,varieties.first(),true)
        val tasks=frozen.tasks.first();val trucks=frozen.truckWorkflows.first()
        var route by mutableStateOf(false)
        ui.setContent {ShiftHudTheme {Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            val f=remember {FrozenTasksViewModel(frozen).also {models.put("f",it)}}
            val c=remember {CountRouteViewModel(store).also {models.put("c",it)}}
            if(route)CountRouteScreen(c) {route=false} else FrozenTasksScreen(f,openCountRoute={route=true}) {}
        }}}
        ui.waitUntil(10000) {(models.get("f") as? FrozenTasksViewModel)?.tasks?.value!=null}
        click("Count Route");show("FROZEN COUNT ROUTE");click("Back to Frozen Tasks")
        assertEquals(tasks,frozen.tasks.first());assertEquals(trucks,frozen.truckWorkflows.first())
    }
}
