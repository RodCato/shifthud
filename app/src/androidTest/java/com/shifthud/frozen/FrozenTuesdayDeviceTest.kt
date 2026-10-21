package com.shifthud.frozen

import android.graphics.*
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.shifthud.ui.theme.ShiftHudTheme
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

class FrozenTuesdayDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val date=LocalDate.of(2026,10,20)
    private val job=SupervisorJob()
    private val file=File(context.cacheDir,"d3-device-test.preferences_pb")
    private val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
    @After fun cleanup()=runBlocking { job.cancelAndJoin();file.delete();Unit }
    @Test fun syntheticPhotoRecognitionAndMultiplePageImport()=runBlocking {
        val photos=(1..2).map { page ->
            val bitmap=Bitmap.createBitmap(1400,800,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=40f;typeface=Typeface.create(Typeface.SANS_SERIF,Typeface.NORMAL) }
            canvas.drawText("Store Display Planning",40f,80f,paint)
            canvas.drawText("Aisle 10 Front - Center",40f,170f,paint)
            canvas.drawText("Item",40f,260f,paint);canvas.drawText("Description",300f,260f,paint)
            canvas.drawText("Initial",1100f,260f,paint);canvas.drawText("Interim",1250f,260f,paint)
            canvas.drawText(if(page==1) "12345" else "12346",40f,350f,paint)
            canvas.drawText(if(page==1) "Pizza Rolls Pepperoni" else "Pizza Rolls Cheese",300f,350f,paint)
            canvas.drawText("5",1100f,350f,paint);canvas.drawText("10",1250f,350f,paint)
            File(context.cacheDir,"synthetic-endcap-$page.png").also { file->file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        try {
            val pages=photos.map { parseEndcapPage(EndcapOcr(context).readDocument(Uri.fromFile(it))) }
            assertTrue(pages.all { it.location=="Aisle 10 Front — Center" })
            assertEquals(listOf("Pizza Rolls Pepperoni","Pizza Rolls Cheese"),pages.flatMap { it.products }.map { it.name })
            store.initialize();store.startTuesday(date)
            store.importProducts(date,date.minusDays(3),pages.first().location!!,pages.flatMap { it.products })
            assertEquals(2,store.tasks.first().count { it.plan.isNotBlank() })
        } finally { photos.forEach { it.delete() } }
    }
    @Test fun narrowLargeFontTuesdayCarryoverIsActionableAndFinalFillIndependent() {
        var scale by mutableStateOf(1f)
        var tasks by mutableStateOf(tuesdayFrozenTemplate(date)+mondayFrozenTemplate().take(1).map { it.copy(state=FrozenState.CARRY_OVER,notes="Keep Monday note",workflows=listOf(date.toString())) })
        ui.setContent { ShiftHudTheme { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) { Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                FrozenTasksContent(tasks,false,null,{}, {},{id,state->tasks=tasks.map { if(it.id==id)it.copy(state=state) else it }},{_,_,_,_,_->},workflow=date.toString())
            } }
        } }
        fun show(text:String) { ui.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
        show("CARRYOVERS (1)")
        File(context.cacheDir,"frozen-d3-normal.png").outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        ui.runOnIdle { scale=2f }
        show("CARRYOVERS (1)");ui.onNodeWithContentDescription("Complete Check freezer and ad wall").performClick()
        assertEquals(FrozenState.COMPLETED,tasks.last().state);assertEquals(FROZEN_MONDAY,tasks.last().date)
        show("ENDCAP — BUILD LAYOUT (4)")
        ui.runOnIdle { tasks=tasks.map { if(it.section.contains("BUILD")) it.copy(state=FrozenState.COMPLETED) else it } }
        show("END OF SHIFT — FINAL FILL (5)")
        assertTrue(tasks.filter { it.section.contains("FINAL FILL") }.all { it.state==FrozenState.TODO })
        show("COMPLETED (5)");ui.onNodeWithText("COMPLETED (5)").performClick();show("Check freezer and ad wall")
        ui.onNodeWithContentDescription("Reopen Check freezer and ad wall").performClick()
        assertEquals("Keep Monday note",tasks.last().notes)
        show("MORNING TRANSITION (9)")
        ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().filter { it.boundsInRoot.width>0 && it.boundsInRoot.height>0 }.forEach { node ->
            node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let { action ->
                val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                assertTrue("Text overflow",layouts.none { it.hasVisualOverflow })
            }
        }
        val image=ui.onRoot().captureToImage()
        File(context.cacheDir,"frozen-d3-large.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun manualReviewCancelThenConfirmPersistsOnlyConfirmedProducts()=runBlocking {
        store.initialize();store.startTuesday(date)
        val before=store.tasks.first()
        var finished=false
        ui.setContent { ShiftHudTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            val model=remember { FrozenTasksViewModel(store) }
            EndcapImportScreen(model,date) { finished=true }
        } } }
        ui.onNodeWithText("Add manual page").performScrollTo().performClick()
        ui.onNodeWithText("Display location — confirm for this page").performScrollTo().performTextInput("Aisle 10 Front — Center")
        ui.onNodeWithText("Product 1 description").performScrollTo().performTextInput("Corrected Cheese Pizza")
        ui.onNodeWithText("Item / SKU (optional)").performScrollTo().performTextInput("12346")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        assertEquals(before,store.tasks.first())
        ui.onNodeWithContentDescription("Confirm page 1 location and products").performScrollTo().performClick()
        File(context.cacheDir,"frozen-d3-review.png").outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        ui.onNodeWithText("Confirm products and create pick list").performScrollTo().performClick()
        ui.waitUntil(10000) { finished }
        assertEquals("Corrected Cheese Pizza",store.tasks.first().single { it.plan.isNotBlank() }.title)
        // Cancelling a second, unsaved draft never reaches repository writes.
        finished=false
        ui.onNodeWithText("Cancel import").performScrollTo().performClick()
        ui.onNodeWithText("Discard").performClick()
        assertTrue(finished);assertEquals(1,store.tasks.first().count { it.plan.isNotBlank() })
    }
}
