package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.*
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
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class FrozenTuesdayTest {
    private val date=LocalDate.of(2026,10,20)
    private val week=LocalDate.of(2026,10,17)
    private val file=Files.createTempDirectory("tuesday-test").resolve("tasks.preferences_pb").toFile()
    private var job=SupervisorJob()
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}),Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"),ZoneId.of("America/Chicago")))
    private var store=create()
    @After fun cleanup()=runBlocking { job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit }
    @Test fun carryoverRetainsIdentityOriginNotesHistoryAndCompletesTuesday()=runBlocking {
        store.initialize();val monday=store.tasks.first()
        store.setState(monday[0].id,FrozenState.COMPLETED)
        store.setState(monday[1].id,FrozenState.BLOCKED)
        store.saveDetails(monday[1].id,monday[1].title,monday[1].description,"Tuesday handoff")
        store.setState(monday[1].id,FrozenState.CARRY_OVER)
        val before=store.tasks.first()
        coroutineScope { repeat(3) { launch { store.startTuesday(date) } } }
        val tasks=store.tasks.first()
        assertEquals(30,tasks.size);assertEquals(30,tasks.map { it.id }.distinct().size)
        assertEquals(before[0],tasks[0]);assertEquals(before[1].copy(workflows=listOf(date.toString())),tasks[1])
        assertTrue(frozenWorkflowTasks(tasks,date.toString()).any { it.id==monday[1].id })
        store.setState(monday[1].id,FrozenState.COMPLETED)
        val completed=frozenWorkflowTasks(store.tasks.first(),date.toString()).single { it.id==monday[1].id }
        assertEquals(FROZEN_MONDAY,completed.date);assertEquals("Tuesday handoff",completed.notes)
        assertEquals(before[1].history,completed.history.dropLast(1))
        assertEquals(date,completed.history.last().at.atZone(ZoneId.of("America/Chicago")).toLocalDate())
        assertFalse(groupFrozenTasks(frozenWorkflowTasks(store.tasks.first(),date.toString())).carryovers.any { it.id==completed.id })
        store.startTuesday(date);assertEquals(completed,store.tasks.first().single { it.id==completed.id })
        val expected=store.tasks.first()
        job.cancelAndJoin();job=SupervisorJob();store=create();store.initialize()
        assertEquals(expected,store.tasks.first());assertEquals(date.toString(),store.selectedWorkflow.first())
    }
    @Test fun laterCarryoversStayVisibleAfterCompletionAndReopening()=runBlocking {
        store.initialize();store.startTuesday(date)
        val original=store.tasks.first().first()
        store.setState(original.id,FrozenState.CARRY_OVER);store.setState(original.id,FrozenState.COMPLETED)
        assertTrue(frozenWorkflowTasks(store.tasks.first(),date.toString()).any { it.id==original.id })
        store.setState(original.id,FrozenState.TODO)
        assertTrue(frozenWorkflowTasks(store.tasks.first(),date.toString()).any { it.id==original.id })
    }
    @Test fun distinctAssignmentsNeverResetPastWorkAndBuildDoesNotCompleteFill()=runBlocking {
        store.initialize();store.startTuesday(date)
        store.tasks.first().filter { it.section.contains("BUILD") }.forEach { store.setState(it.id,FrozenState.COMPLETED) }
        val first=store.tasks.first()
        assertTrue(first.filter { it.section.contains("FINAL FILL") }.all { it.state==FrozenState.TODO })
        store.startTuesday(date.plusWeeks(1))
        first.forEach { assertEquals(it,store.tasks.first().single { row->row.id==it.id }) }
        assertEquals(50,store.tasks.first().size)
        store.selectWorkflow("");assertEquals(10,frozenWorkflowTasks(store.tasks.first(),"").size)
    }
    @Test fun reviewedMultiPageProductsDeduplicateWithoutLosingVarietiesOrProgress()=runBlocking {
        store.initialize();store.startTuesday(date)
        val products=listOf(EndcapProduct("Pizza Rolls Pepperoni","12345"),EndcapProduct("Pizza Rolls Cheese","12346"),EndcapProduct("Mixed Vegetables"))
        store.importProducts(date,week,"Aisle 10 Front — Center",products+products)
        var picks=store.tasks.first().filter { it.plan.isNotBlank() }
        assertEquals(3,picks.size)
        store.setState(picks[0].id,FrozenState.COMPLETED)
        store.importProducts(date,week,"aisle 10 front center",products)
        picks=store.tasks.first().filter { it.plan.isNotBlank() };assertEquals(3,picks.size)
        assertEquals(FrozenState.COMPLETED,picks[0].state)
        store.editProduct(picks[1].id,"Pizza Rolls Triple Meat","12347")
        try { store.editProduct(picks[1].id,"Duplicate","12345");fail() } catch(_:IllegalArgumentException) { }
        try { store.saveDetails(picks[1].id,picks[2].title,"","");fail() } catch(_:IllegalArgumentException) { }
        store.removeProduct(picks[2].id)
        val expected=store.tasks.first();job.cancelAndJoin();job=SupervisorJob();store=create()
        assertEquals(expected,store.tasks.first())
        store.setState(picks[0].id,FrozenState.TODO)
        assertEquals(FrozenState.TODO,store.tasks.first().single { it.id==picks[0].id }.state)
    }
    @Test fun invalidOrUnconfirmedDraftNeverPersists()=runBlocking {
        store.initialize();val before=store.tasks.first()
        val draft=EndcapProduct("Edited by user") // Review/cancel stays outside repository.
        assertEquals(before,store.tasks.first())
        try { store.importProducts(date,week,"",listOf(draft));fail() } catch(_:IllegalArgumentException) { }
        try { store.importProducts(date,week.plusDays(1),"Center",listOf(draft));fail() } catch(_:IllegalArgumentException) { }
        try { store.importProducts(date,week,"Center",listOf(EndcapProduct("")));fail() } catch(_:IllegalArgumentException) { }
        assertEquals(before,store.tasks.first())
    }
    @Test fun originalV1PayloadDecodesWithoutMigrationOrReset() {
        val raw="""{"version":1,"tasks":[{"id":"old","date":"2026-10-19","title":"Original","description":"Ref","state":"CARRY_OVER","notes":"Keep","history":[{"state":"CARRY_OVER","at":"2026-10-19T20:00:00Z"}]}]}"""
        val old=store.decode(raw).single();assertEquals("Keep",old.notes);assertTrue(old.workflows.isEmpty())
        assertEquals(listOf(old),store.decode(store.encode(listOf(old))))
    }
    private fun document(vararg text:String)=OcrDocument(listOf(OcrBlock(OcrPiece("",0,0,1000,1000),text.mapIndexed { i,line -> OcrLine(OcrPiece(line,10,i*30,900,i*30+20)) })))
    @Test fun syntheticSheetExtractsOnlyTargetProductsAndNoQuantities() {
        val result=parseEndcapPage(document("Store Display Planning","Aisle 10 Front — Center","Item Number Description Initial Interim",
            "12345 Totino's Pizza Rolls Pepperoni 5 10","12346 Totino's Pizza Rolls Cheese 4 8","Birds Eye Mixed Vegetables",
            "Aisle 12 Front — Left","98765 Unrelated Product 5"))
        assertEquals("Aisle 10 Front — Center",result.location)
        assertEquals(listOf(EndcapProduct("Totino's Pizza Rolls Pepperoni","12345"),EndcapProduct("Totino's Pizza Rolls Cheese","12346"),EndcapProduct("Birds Eye Mixed Vegetables")),result.products)
    }
    @Test fun geometryIgnoresQuantityColumnsAndPreservesNumericVarietyNames() {
        fun piece(text:String,x:Int,y:Int)=OcrPiece(text,x,y,x+100,y+20)
        val elements=listOf(piece("Aisle 10 Front Center",0,0),piece("SKU",0,50),piece("Description",200,50),piece("Initial",800,50),piece("Interim",950,50),
            piece("12345",0,100),piece("Brand 4 Cheese Pizza",200,100),piece("15",800,100),piece("20",950,100))
        val doc=OcrDocument(listOf(OcrBlock(piece("",0,0),elements.reversed().map { OcrLine(it) })))
        assertEquals(listOf(EndcapProduct("Brand 4 Cheese Pizza","12345")),parseEndcapPage(doc).products)
        assertEquals("Brand 4 Cheese Pizza",parseEndcapPage(document("Aisle 10 Front Center","12345 Brand 4 Cheese Pizza 5 10")).products.single().name)
    }
    @Test fun unknownCroppedOrOtherDisplayRequiresManualReview() {
        assertTrue(parseEndcapPage(document("12345 Cropped Product")).products.isEmpty())
        assertNull(parseEndcapPage(document("Aisle 11 Front Center","12345 Wrong Product")).location)
        assertTrue(parseEndcapPage(document("Aisle 10 Front Center","12345 5 10")).products.isEmpty())
    }
    @Test fun nameOnlyNormalizationDoesNotMergeVarietiesOrDifferentSkus() {
        assertEquals(3,distinctEndcapProducts(listOf(EndcapProduct("Corn"),EndcapProduct(" corn "),EndcapProduct("Corn sweet"),EndcapProduct("Corn sweet","456"),EndcapProduct("Corn sweet","789"))).size)
        assertFalse(sameEndcapProduct(EndcapProduct("Same","123"),EndcapProduct("Same","456")))
    }
}
