package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shifthud.frozen.*
import com.shifthud.ui.paystub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class EndcapReliabilityTest {
    private val file=Files.createTempDirectory("endcap-repair").resolve("tasks.preferences_pb").toFile()
    private var job=SupervisorJob()
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
    private var store=create()
    private val date=LocalDate.of(2026,10,20);private val week=date.minusDays(3)
    @After fun cleanup()=runBlocking {job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit}
    private fun p(text:String,x:Int,y:Int,w:Int=120,h:Int=20)=OcrPiece(text,x,y,x+w,y+h)
    private fun doc(vararg pieces:OcrPiece)=OcrDocument(pieces.reversed().map {OcrBlock(it,listOf(OcrLine(it)))})
    @Test fun cropSliderBoundaryAllowsFloatingPointRounding() {
        assertEquals(0.99f,EndcapCrop(left=1f-0.01f,top=1f-0.01f).left)
        assertThrows(IllegalArgumentException::class.java) { EndcapCrop(left=0.999f) }
    }
    @Test fun unknownLocationNeverErasesRecognizedProducts() {
        val result=parseEndcapPage(doc(p("12345 Talenti Vanilla",200,100,400),p("12346 Talenti Mango",200,140,400)))
        assertEquals(2,result.products.size);assertTrue(result.candidates.all {it.location==null})
        assertTrue(reviewRows(result).none {it.included});assertTrue(result.warning.contains("association"))
    }
    @Test fun splitHeadingAndOcrSubstitutionsAssociateWithoutPerRowLocation() {
        val result=parseEndcapPage(doc(p("Endcap — Aisle 1O",0,0,180),p("Front",0,23),p("Center",0,46),
            p("12345 Talenti Vanilla",250,90,400),p("12346 Talenti Chocolate",250,130,400)))
        assertEquals(TARGET_ENDCAP,result.location);assertEquals(2,result.products.size)
        assertTrue(result.candidates.all {it.location==TARGET_ENDCAP})
    }
    @Test fun mergedLocationCellsWrappedDescriptionsAndDifferentSections() {
        val result=parseEndcapPage(doc(p("Aisle 10 Front Center",0,0,160,80),
            p("Item",200,0),p("Description",400,0),p("Initial",900,0),
            p("12345",200,100),p("Pizza Rolls",400,100,250),p("8",900,100),
            p("Triple Meat",400,125,250),p("12346",200,170),p("Pizza Rolls Cheese",400,170,400),
            p("Aisle 12 Front Left",0,220,180),p("99999",200,260),p("Other Product",400,260,300)))
        assertEquals(3,result.products.size);assertEquals("Pizza Rolls Triple Meat",result.products.first().name)
        assertEquals("12346",result.products[1].sku)
        assertEquals("Aisle 12 Front — Left",result.candidates.last().location)
        assertFalse(reviewRows(result).last().included)
    }
    @Test fun parsingKeepsDuplicateRowsAndVarietiesUntilReviewedImport() {
        val result=parseEndcapPage(doc(p("12345 Pepperoni",200,0,300),p("12346 Cheese",200,40,300),p("12345 Pepperoni",200,80,300)))
        assertEquals(3,result.products.size)
        assertEquals(2,distinctEndcapProducts(result.products).size)
    }
    @Test fun rejectedRowsAndEmptyOcrHaveDifferentDiagnostics() {
        assertTrue(parseEndcapPage(OcrDocument(emptyList())).warning.contains("no text"))
        val result=parseEndcapPage(doc(p("12345 10 20",0,0,300)))
        assertTrue(result.products.isEmpty());assertTrue(result.rejected.isNotEmpty())
        assertTrue(result.warning.contains("text but no products"))
    }
    @Test fun retryMergePreservesManualCorrectionsSelectionsAndGroups() {
        val old=listOf(EndcapReviewRow(EndcapProduct("Corrected Vanilla","12345","Gelato","Desserts — Right"),included=false))
        val fresh=listOf(EndcapReviewRow(EndcapProduct("Bad OCR","12345")),EndcapReviewRow(EndcapProduct("Mango","12346")))
        val merged=mergeEndcapRetry(old,fresh)
        assertEquals(old.first(),merged.first());assertEquals(2,merged.size)
    }
    @Test fun familiesEligibleVarietiesAndProgressPersistWithoutTouchingOtherWorkflows()=runBlocking {
        store.initialize();store.startTuesday(date);store.startWednesday(date.plusDays(1));store.setTruckArrived(date.plusDays(1),true)
        val original=store.tasks.first();val truck=store.truckWorkflows.first()
        val rows=listOf(EndcapProduct("Talenti Vanilla","12345","Talenti","Desserts — Right"),EndcapProduct("Talenti Mango","12346","Talenti","Desserts — Right"))
        store.importProducts(date,week,TARGET_ENDCAP,rows)
        val family=store.tasks.first().single {it.family.isNotBlank()}
        assertEquals(2,family.eligible.size);assertEquals("Desserts — Right",family.category)
        store.chooseEndcapVariety(family.id,rows[0],true);store.setState(family.id,FrozenState.COMPLETED)
        store.saveDetails(family.id,"My gelato selection",family.description,"Only use what fits")
        store.importProducts(date,week,TARGET_ENDCAP,rows+EndcapProduct("Talenti Raspberry","12347","Talenti","Desserts — Right"))
        val updated=store.tasks.first().single {it.id==family.id}
        assertEquals("My gelato selection",updated.title);assertEquals(FrozenState.COMPLETED,updated.state)
        assertEquals(3,updated.eligible.size);assertEquals(listOf(endcapProductIdentity(rows[0])),updated.chosen)
        original.forEach {old->assertEquals(old,store.tasks.first().single {it.id==old.id})};assertEquals(truck,store.truckWorkflows.first())
        assertTrue(store.tasks.first().filter {it.section.contains("FINAL FILL")}.all {it.state==FrozenState.TODO})
        val expected=store.tasks.first();job.cancelAndJoin();job=SupervisorJob();store=create();store.initialize()
        assertEquals(expected,store.tasks.first());assertEquals(truck,store.truckWorkflows.first())
    }
    @Test fun reimportPreservesRenamedNameOnlyAndIndividualSkuCorrections()=runBlocking {
        store.initialize();store.startTuesday(date)
        store.importProducts(date,week,TARGET_ENDCAP,listOf(EndcapProduct("Original name"),EndcapProduct("OCR cheese","12345")))
        val tasks=store.tasks.first().filter {it.plan.isNotBlank()}
        store.editProduct(tasks.first {it.sku.isBlank()}.id,"Manual corrected name","")
        store.editProduct(tasks.first {it.sku.isNotBlank()}.id,"Correct cheese","12346")
        val before=store.tasks.first()
        store.importProducts(date,week,TARGET_ENDCAP,listOf(EndcapProduct("Original name"),EndcapProduct("OCR cheese","12345")))
        assertEquals(before,store.tasks.first())
        assertTrue(endcapImportPreview(before,week,TARGET_ENDCAP,listOf(EndcapProduct("Original name"))).single().contains("Keep existing"))
    }
    @Test fun correctedNameOnlyOcrRowsKeepTheirSourceIdentityAcrossReimport()=runBlocking {
        store.initialize();store.startTuesday(date)
        val parsed=parseEndcapPage(doc(p("Talenti Vanila",0,0,300)))
        val original=reviewRows(parsed).single().product
        val corrected=original.copy(name="Talenti Vanilla",family="Gelato")
        store.importProducts(date,week,TARGET_ENDCAP,listOf(corrected))
        val before=store.tasks.first()
        store.importProducts(date,week,TARGET_ENDCAP,listOf(original))
        assertEquals(before,store.tasks.first())
        assertEquals(1,mergeEndcapRetry(listOf(EndcapReviewRow(corrected)),listOf(EndcapReviewRow(original))).size)
    }
    @Test fun correctedOcrSkuSurvivesRetryAndReimport()=runBlocking {
        store.initialize();store.startTuesday(date)
        val original=reviewRows(parseEndcapPage(doc(p("12345 Talenti Vanila",0,0,400)))).single().product
        val corrected=original.copy(name="Talenti Vanilla",sku="12346")
        assertEquals(1,mergeEndcapRetry(listOf(EndcapReviewRow(corrected)),listOf(EndcapReviewRow(original))).size)
        store.importProducts(date,week,TARGET_ENDCAP,listOf(corrected))
        val before=store.tasks.first()
        store.importProducts(date,week,TARGET_ENDCAP,listOf(original))
        assertEquals(before,store.tasks.first())
    }
    @Test fun noImplicitBrandGroupingAndGroupingCannotMergeDifferentSkus()=runBlocking {
        store.initialize();store.startTuesday(date)
        store.importProducts(date,week,TARGET_ENDCAP,listOf(EndcapProduct("Talenti Vanilla","12345"),EndcapProduct("Talenti Mango","12346")))
        assertEquals(2,store.tasks.first().count {it.plan.isNotBlank()});assertTrue(store.tasks.first().all {it.family.isBlank()})
        assertFalse(sameEndcapProduct(EndcapProduct("Same","12345"),EndcapProduct("Same","12346")))
    }
}
