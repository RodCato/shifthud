package com.shifthud.frozen

import android.graphics.*
import android.net.Uri
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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

class EndcapReliabilityDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val date=LocalDate.of(2026,10,20)
    private val job=SupervisorJob();private val models=ViewModelStore()
    private val directory=File(context.cacheDir,"endcap-reliability-fixture").apply {mkdirs()}
    private val file=File(directory,"tasks.preferences_pb")
    private val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
    @After fun cleanup()=runBlocking {ui.runOnIdle {models.clear()};job.cancelAndJoin();directory.deleteRecursively();Unit}
    private val names=listOf("Talenti Vanilla","Talenti Mango Sorbet","Talenti Chocolate","Pizza Rolls Pepperoni","Pizza Rolls Cheese","Pizza Rolls Triple Meat")
    private fun sheet(name:String="sheet.png",rotation:Int=0,exif:Boolean=false,perspective:Boolean=false):File {
        val bitmap=Bitmap.createBitmap(2400,3000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=30f;typeface=Typeface.create(Typeface.SANS_SERIF,Typeface.NORMAL)}
        canvas.drawText("Store Display Planning — Detailed Display Plan",50f,100f,paint)
        canvas.drawText("Item",400f,220f,paint);canvas.drawText("Description",700f,220f,paint)
        canvas.drawText("Initial",2000f,220f,paint);canvas.drawText("Interim",2200f,220f,paint)
        canvas.drawText("Endcap Aisle 10",30f,300f,paint);canvas.drawText("Front",30f,340f,paint);canvas.drawText("Center",30f,380f,paint)
        names.forEachIndexed { index,text ->
            val y=450f+index*95f
            canvas.drawText("${123450+index}",400f,y,paint)
            if(index==5) {canvas.drawText("Pizza Rolls",700f,y,paint);canvas.drawText("Triple Meat",700f,y+34,paint)} else canvas.drawText(text,700f,y,paint)
            canvas.drawText("10",2000f,y,paint);canvas.drawText("20",2200f,y,paint)
        }
        canvas.drawText("Aisle 12 Front Left",30f,1250f,paint)
        canvas.drawText("999999",400f,1350f,paint);canvas.drawText("Unrelated Cereal",700f,1350f,paint)
        val line=Paint().apply {color=Color.LTGRAY;strokeWidth=2f}
        listOf(20f,350f,650f,1950f,2150f,2370f).forEach {canvas.drawLine(it,250f,it,1450f,line)}
        var result=bitmap
        if(perspective) {
            val matrix=Matrix();matrix.setPolyToPoly(floatArrayOf(0f,0f,2400f,0f,2400f,3000f,0f,3000f),0,floatArrayOf(20f,12f,2370f,0f,2400f,2980f,0f,3000f),0,4)
            result=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true);if(result!==bitmap)bitmap.recycle()
        }
        if(rotation!=0) {val old=result;result=Bitmap.createBitmap(old,0,0,old.width,old.height,Matrix().apply {postRotate(rotation.toFloat())},true);if(result!==old)old.recycle()}
        val output=File(directory,name);output.outputStream().use {result.compress(if(exif)Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,98,it)};result.recycle()
        if(exif)ExifInterface(output).apply {setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
        return output
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(context.cacheDir,"endcap-d3a-$name.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}
        finally {screenshot.recycle()}
    }
    @Test fun realEngineDenseSheetOrientationCropAndPerspective()=runBlocking {
        val original=sheet();val ocr=EndcapOcr(context)
        val scan=ocr.scan(Uri.fromFile(original));val parsed=parseEndcapPage(scan.document)
        assertTrue(scan.recognizedText.contains("Talenti"));assertEquals(2400,scan.width)
        // This is the exact previous location gate: real OCR has products but no single-line heading match.
        val oldRows=com.shifthud.ui.paystub.ocrRows(scan.document.lines.map {it.bounds})
        assertFalse(oldRows.lines().any {Regex("(?i)aisle\\s*10\\s*front\\s*[-—–:]?\\s*center").containsMatchIn(it)})
        assertEquals(names,parsed.candidates.filter {it.location==TARGET_ENDCAP}.map {it.product.name})
        assertEquals("Unrelated Cereal",parsed.candidates.single {it.location=="Aisle 12 Front — Left"}.product.name)
        val cropped=ocr.scan(Uri.fromFile(original),crop=EndcapCrop(0f,0.08f,1f,0.36f))
        assertTrue(cropped.height<scan.height);assertFalse(parseEndcapPage(cropped.document).products.any {it.name.contains("Cereal")})
        val landscape=sheet("landscape.png",270)
        val rotated=ocr.scan(Uri.fromFile(landscape),90)
        assertEquals(names,parseEndcapPage(rotated.document).candidates.filter {it.location==TARGET_ENDCAP}.map {it.product.name})
        val exif=ocr.scan(Uri.fromFile(sheet("exif.jpg",270,true)))
        assertTrue(exif.orientation.contains("EXIF 6"));assertEquals(6,parseEndcapPage(exif.document).candidates.count {it.location==TARGET_ENDCAP})
        val skewed=ocr.scan(Uri.fromFile(sheet("perspective.png",perspective=true)))
        assertTrue(parseEndcapPage(skewed.document).products.count {it.sku.startsWith("12345")}>=5)
        val preview=decodeEndcapImage(context,Uri.fromFile(original),maxPixels=100_000,maxEdge=500)
        assertTrue(preview.bitmap.width.toLong()*preview.bitmap.height<=100_000);preview.bitmap.recycle()
    }
    @Test fun photoToReviewedFamilyChecklistWithDiagnosticsAndSafeRetry()=runBlocking {
        store.initialize();store.startTuesday(date)
        val original=store.tasks.first();val photo=sheet()
        var finished by mutableStateOf(false)
        ui.setContent {ShiftHudTheme {Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            val vm=remember {FrozenTasksViewModel(store).also {models.put("vm",it)}}
            val tasks by vm.tasks.collectAsStateWithLifecycle()
            if(!finished)EndcapImportScreen(vm,date,listOf(Uri.fromFile(photo))) {finished=true}
            else FrozenTasksContent(tasks,false,null,{}, {},vm::state,vm::save,workflow=date.toString(),chooseVariety=vm::chooseVariety)
        } } }
        ui.waitUntil(30000) {ui.onAllNodesWithText("Product 1 description").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(10000) {ui.onAllNodesWithContentDescription("Source page 1",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("Source page 1",useUnmergedTree=true).performScrollTo();capture("preview")
        ui.onNodeWithText("OCR Diagnostics").performScrollTo().performClick()
        ui.onNodeWithText("View Recognized Text").performScrollTo();capture("diagnostics")
        ui.onNodeWithText("Product 1 description").performScrollTo();capture("candidates")
        ui.onNodeWithText("Display location — confirm for this page").performScrollTo().performTextInput(TARGET_ENDCAP)
        ui.onNodeWithText("Product 1 description").performScrollTo().performTextClearance()
        ui.onNodeWithText("Product 1 description").performTextInput("Talenti Vanilla corrected")
        androidx.test.espresso.Espresso.closeSoftKeyboard();capture("correction")
        ui.onNodeWithText("Retry OCR").performScrollTo().performClick()
        ui.onNodeWithText("Keep edits and add candidates").performClick()
        ui.waitUntil(30000) {ui.onAllNodesWithText("Talenti Vanilla corrected").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(30000) {ui.onAllNodes(hasText("Retry OCR") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        // Crop uses the same explicit reconciliation dialog; cancel must preserve edits.
        ui.onNodeWithText("Crop to Display Section").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Crop bottom boundary").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {it(0.36f)}
        ui.onNodeWithText("Use crop and review retry").performClick()
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Optional family · product 1").performScrollTo().performClick()
        ui.onNodeWithText("Family name (blank = individual)").performScrollTo().performTextInput("Talenti")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithContentDescription("Confirm page 1 location and products").performScrollTo().performClick()
        ui.onNodeWithText("Confirm products and create pick list").performScrollTo().performClick()
        ui.onNodeWithText("Save reviewed pick list").assertIsDisplayed();capture("confirmed")
        ui.onNodeWithText("Save reviewed pick list").performClick()
        ui.waitUntil(10000) {finished}
        val imported=store.tasks.first().filter {it.plan.isNotBlank()}
        assertEquals(6,imported.size);assertTrue(imported.none {it.title.contains("Cereal")})
        val family=imported.single {it.family=="Talenti"};assertEquals("Talenti Vanilla corrected",family.eligible.single().name)
        original.forEach {old->assertEquals(old,store.tasks.first().single {it.id==old.id})}
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("ENDCAP — PICK FOR LAYOUT (6)"));capture("checklist")
    }
    @Test fun cameraAndMultiPhotoPickerContractsFeedRealRecognition()=runBlocking {
        store.initialize();store.startTuesday(date)
        val photo=sheet();val secondPhoto=photo.copyTo(File(directory,"second-page.png"),overwrite=true)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var cameraCalls=0;var pickerCalls=0
        val monitor=object:android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? {
                if(intent.action==android.provider.MediaStore.ACTION_IMAGE_CAPTURE) {
                    cameraCalls++
                    @Suppress("DEPRECATION") val output=intent.getParcelableExtra<Uri>(android.provider.MediaStore.EXTRA_OUTPUT)!!
                    context.contentResolver.openOutputStream(output)!!.use {out->photo.inputStream().use {it.copyTo(out)}}
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,android.content.Intent())
                }
                if(intent.action?.contains("PICK_IMAGES")==true || intent.action==android.content.Intent.ACTION_OPEN_DOCUMENT || intent.action==android.content.Intent.ACTION_GET_CONTENT) {
                    pickerCalls++
                    val uri=Uri.fromFile(photo)
                    val clip=android.content.ClipData.newRawUri("Synthetic page",uri).apply {addItem(android.content.ClipData.Item(Uri.fromFile(secondPhoto)))}
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,android.content.Intent().apply { clipData=clip })
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ui.setContent {ShiftHudTheme {Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                val vm=remember {FrozenTasksViewModel(store).also {models.put("vm",it)}}
                EndcapImportScreen(vm,date) {}
            } } }
            ui.onNodeWithText("Take photo").performScrollTo().performClick()
            ui.waitUntil(30000) {ui.onAllNodesWithText("Product 1 description").fetchSemanticsNodes().isNotEmpty()}
            ui.waitUntil(30000) {ui.onAllNodes(hasText("Select photos / pages") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("Select photos / pages").performScrollTo().performClick()
            ui.waitUntil(60000) {ui.onAllNodesWithText("Page 3").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(1,cameraCalls);assertEquals(1,pickerCalls)
            assertEquals(0,store.tasks.first().count {it.plan.isNotBlank()})
        } finally {instrumentation.removeMonitor(monitor)}
    }
    @Test fun manualFallbackAtLargeFontStillExplainsAndConfirms()=runBlocking {
        store.initialize();store.startTuesday(date)
        var finished=false
        ui.setContent {ShiftHudTheme {val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                val vm=remember {FrozenTasksViewModel(store).also {models.put("vm",it)}}
                EndcapImportScreen(vm,date) {finished=true}
            } }
        } }
        ui.onNodeWithText("Add manual page").performScrollTo().performClick()
        ui.onNodeWithText("Display location — confirm for this page").performScrollTo().performTextInput(TARGET_ENDCAP)
        ui.onNodeWithText("Product 1 description").performScrollTo().performTextInput("Manual fallback product")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithContentDescription("Confirm page 1 location and products").performScrollTo().performClick();capture("manual-large")
        ui.onNodeWithText("Confirm products and create pick list").performScrollTo().performClick()
        ui.onNodeWithText("Save reviewed pick list").performClick();ui.waitUntil(10000) {finished}
        assertEquals("Manual fallback product",store.tasks.first().single {it.plan.isNotBlank()}.title)
    }
}
