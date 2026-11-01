package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shifthud.frozen.*
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
class FrozenDailyWorkflowTest {
    private val file=Files.createTempDirectory("daily-frozen").resolve("tasks.preferences_pb").toFile()
    private var job=SupervisorJob()
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}),Clock.fixed(Instant.parse("2026-11-02T18:00:00Z"),ZoneOffset.UTC))
    private var store=create()
    private val monday=LocalDate.of(2026,11,2)
    @After fun close()=runBlocking {job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit}
    private suspend fun tasks()=store.tasks.first()
    @Test fun automaticOpeningDoesNotSeedOldMondayOrDuplicate()=runBlocking {
        coroutineScope {repeat(5) {launch {store.openDaily(monday)}}}
        assertEquals(10,tasks().size);assertTrue(tasks().all {it.date==monday});assertEquals(dailyWorkflowId(monday),store.selectedWorkflow.first())
        assertTrue(store.truckWorkflows.first().isEmpty())
    }
    @Test fun loadedWorkflowPreservesNotesAndCompletedHistory()=runBlocking {
        store.openDaily(monday);val first=tasks().first()
        store.saveDetails(first.id,"Edited task",first.description,"Note")
        store.setDailyState(monday,first.id,FrozenState.COMPLETED)
        val original=tasks();store.openDaily(monday);assertEquals(original,tasks())
    }
    @Test fun sundayManualWorkAppearsMondayAndCompletionNeverReturnsTuesdayAfterRestart()=runBlocking {
        val sunday=monday.minusDays(1);store.openDaily(sunday)
        store.add("Unfinished delivery","Existing field note","Sunday source")
        val original=tasks().single();store.openDaily(monday)
        val carry=dailyFrozenRows(tasks(),monday).single {it.task.id==original.id}
        assertEquals(FrozenState.CARRY_OVER,carry.task.state);assertEquals(sunday,carry.task.date)
        store.setDailyState(monday,carry.task.id,FrozenState.COMPLETED)
        job.cancelAndJoin();job=SupervisorJob();store=create();store.openDaily(monday.plusDays(1))
        assertFalse(dailyFrozenRows(tasks(),monday.plusDays(1)).any { row->row.sources.any {it.id==original.id} })
        assertEquals("Sunday source",tasks().single {it.id==original.id}.notes)
        assertTrue(dailyFrozenRows(tasks(),monday).any {it.task.id==original.id && it.task.state==FrozenState.COMPLETED})
    }
    @Test fun matchingTemplateIdentitiesMergeAndCompleteBothSources()=runBlocking {
        val prior=monday.minusDays(6);store.openDaily(prior);store.openDaily(monday)
        val rows=dailyFrozenRows(tasks(),monday)
        val backstock=rows.single {row->row.sources.any {it.id=="monday-$monday-3"}}
        assertEquals("Run Frozen backstock",backstock.task.title);assertEquals(2,backstock.sources.size);assertEquals(setOf(prior,monday),backstock.sources.map{it.date}.toSet())
        store.setDailyState(monday,backstock.task.id,FrozenState.COMPLETED)
        assertTrue(tasks().filter {it.id in backstock.sources.map{source->source.id}}.all{it.state==FrozenState.COMPLETED})
    }
    @Test fun similarManualTitlesNeverMerge()=runBlocking {
        store.openDaily(monday.minusDays(1));store.add("Run Frozen backstock","Manual unrelated detail","")
        store.openDaily(monday);assertEquals(2,dailyFrozenRows(tasks(),monday).count {it.task.title=="Run Frozen backstock"})
    }
    @Test fun multiDayAbsenceDoesNotMakeCopiesOrInventInterveningWork()=runBlocking {
        store.openDaily(monday);val old=tasks().map{it.id}.toSet();val later=monday.plusDays(12)
        repeat(3){store.openDaily(later)}
        assertEquals(old,tasks().map{it.id}.toSet());assertEquals(10,dailyFrozenRows(tasks(),later).size)
        assertEquals(listOf(later,monday),store.dailyDates.first())
    }
    @Test fun explicitDismissalSurvivesNextDayAndRestart()=runBlocking {
        store.openDaily(monday);val task=tasks().first();store.openDaily(monday.plusDays(1))
        store.setDailyState(monday.plusDays(1),task.id,FrozenState.TODO)
        assertTrue(tasks().single{it.id==task.id}.carryoverExcluded)
        job.cancelAndJoin();job=SupervisorJob();store=create();store.openDaily(monday.plusDays(2))
        assertFalse(dailyFrozenRows(tasks(),monday.plusDays(2)).any {row->row.sources.any{it.id==task.id}})
    }
    @Test fun historicalBrowseDoesNotPullFutureTasksOrOverwriteHistory()=runBlocking {
        store.openDaily(monday);val original=tasks();store.openDaily(monday.minusDays(1))
        assertTrue(dailyFrozenRows(tasks(),monday.minusDays(1)).isEmpty());assertEquals(original,tasks())
        store.openDaily(monday.plusDays(1));assertEquals(original.map{it.id},tasks().filter{it.date==monday}.map{it.id})
    }
    @Test fun existingManualWorkflowIsNotResetOrRefilled()=runBlocking {
        store.initialize();store.startTuesday(monday.plusDays(1));val old=tasks()
        store.openDaily(monday.plusDays(1));assertEquals(old.map{it.id},tasks().map{it.id})
        assertEquals(old.map{it.history},tasks().map{it.history})
    }
    @Test fun wednesdayReusesTruckTemplatesWithoutRecordingArrival()=runBlocking {
        val wed=monday.plusDays(2);store.openDaily(wed)
        assertEquals(wednesdayFrozenTemplate(wed).map{it.id},tasks().map{it.id});assertNull(store.truckWorkflows.first().single().arrival)
        store.setTruckArrived(wed,true);val before=store.truckWorkflows.first();store.openDaily(wed);assertEquals(before,store.truckWorkflows.first())
    }
    @Test fun automaticTemplatesDoNotCreateManualAssignmentLinks()=runBlocking {
        val tuesday=monday.plusDays(1);val wednesday=monday.plusDays(2)
        store.openDaily(tuesday);store.openDaily(wednesday)
        assertTrue(tasks().all { task->task.workflows.all { it.startsWith(DAILY_PREFIX) } })
        val original=tasks()
        store.startTuesday(tuesday);store.startWednesday(wednesday)
        assertEquals(original.map{it.id},tasks().map{it.id})
        assertTrue(tasks().filter{it.date==tuesday}.all{tuesday.toString() in it.workflows})
        assertTrue(tasks().filter{it.date==wednesday}.all{wednesdayWorkflowId(wednesday) in it.workflows})
        original.forEach { old->assertEquals(old.copy(workflows=tasks().single{it.id==old.id}.workflows),tasks().single{it.id==old.id}) }
    }
    @Test fun legacyDataAndOcrFieldsRoundTripUnchanged()=runBlocking {
        store.initialize();store.startTuesday(monday.plusDays(1));store.importProducts(monday.plusDays(1),monday.minusDays(2),"Test display",listOf(EndcapProduct("Synthetic product","123")))
        val before=tasks();store.openDaily(monday.plusDays(2))
        before.forEach { old->val now=tasks().single{it.id==old.id};assertEquals(old.copy(workflows=now.workflows),now) }
    }
    @Test fun emptyUnknownDayIsSavedWithoutInventingProcedures()=runBlocking {
        val day=monday.plusDays(3);store.openDaily(day);store.openDaily(day)
        assertTrue(tasks().isEmpty());assertEquals(listOf(day),store.dailyDates.first())
    }
}
