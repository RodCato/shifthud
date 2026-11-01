package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}).also { dataStore=it },Clock.fixed(Instant.parse("2026-11-02T18:00:00Z"),ZoneOffset.UTC))
    private var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>?=null
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
        val original=tasks().single {it.title=="Unfinished delivery"};store.openDaily(monday)
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
        store.openDaily(monday);val original=tasks();store.openDaily(monday.minusDays(2))
        assertTrue(dailyFrozenRows(tasks(),monday.minusDays(2)).isEmpty());assertEquals(original,tasks())
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
    @Test fun sundayTemplateIsOrderedIndependentAndNeverAutoCompleted()=runBlocking {
        val sunday=monday.minusDays(1)
        coroutineScope {repeat(5) {launch {store.openDaily(sunday)}}}
        val saved=tasks()
        assertEquals(sundayFrozenTemplate(sunday),saved)
        assertEquals(7,saved.size)
        assertTrue(saved.all {it.state==FrozenState.TODO && it.history.isEmpty()})
        assertTrue(saved.first().description.contains("FF Frozen expected list"))
        assertTrue(saved[4].description.contains("authorized personnel before merchandise is worked"))
        saved.forEach { task ->
            store.setDailyState(sunday,task.id,FrozenState.COMPLETED)
            assertEquals(1,tasks().count {it.state==FrozenState.COMPLETED})
            store.setDailyState(sunday,task.id,FrozenState.TODO)
        }
        assertTrue(store.truckWorkflows.first().isEmpty())
    }
    @Test fun previouslyOpenedSundayGetsMissingTemplateOnceWithoutOverwritingSavedFacts()=runBlocking {
        val sunday=monday.minusDays(1)
        val saved=FrozenTask("sunday-$sunday-1",sunday,"Edited review","Saved detail",FrozenState.COMPLETED,"Keep note")
        dataStore!!.edit { values ->
            values[androidx.datastore.preferences.core.stringPreferencesKey("tasks_v1")]=store.encode(listOf(saved))
            values[androidx.datastore.preferences.core.stringSetPreferencesKey("daily_workflow_dates")]=setOf(sunday.toString())
        }
        store.openDaily(sunday)
        assertEquals(7,tasks().size)
        assertEquals(saved.copy(workflows=listOf(dailyWorkflowId(sunday))),tasks().first())
        val before=tasks();job.cancelAndJoin();job=SupervisorJob();store=create()
        store.openDaily(sunday);assertEquals(before,tasks())
    }
    @Test fun emptyPreviouslyVisitedSundayIsRepaired()=runBlocking {
        val sunday=monday.minusDays(1)
        dataStore!!.edit { values ->
            values[androidx.datastore.preferences.core.stringPreferencesKey("tasks_v1")]=store.encode(emptyList())
            values[androidx.datastore.preferences.core.stringSetPreferencesKey("daily_workflow_dates")]=setOf(sunday.toString())
        }
        store.openDaily(sunday);store.openDaily(sunday)
        assertEquals(sundayFrozenTemplate(sunday),tasks())
    }
    @Test fun sundayCarryoversMergeOnlySharedMondayDefinitionsAndStayCompletedTuesday()=runBlocking {
        val sunday=monday.minusDays(1);store.openDaily(sunday);store.openDaily(monday)
        val rows=dailyFrozenRows(tasks(),monday)
        assertEquals(15,rows.size) // Seven Sunday + ten Monday, two explicit shared definitions.
        for(index in listOf(3,4)) {
            val row=rows.single {it.task.id=="monday-$monday-$index"}
            assertEquals(setOf(sunday,monday),row.sources.map{it.date}.toSet())
            store.setDailyState(monday,row.task.id,FrozenState.COMPLETED)
        }
        val completed=tasks().filter{it.state==FrozenState.COMPLETED}.map{it.id}.toSet()
        assertEquals(4,completed.size)
        job.cancelAndJoin();job=SupervisorJob();store=create();store.openDaily(monday.plusDays(1))
        assertTrue(dailyFrozenRows(tasks(),monday.plusDays(1)).flatMap{it.sources}.none{it.id in completed})
        assertEquals(5,dailyFrozenRows(tasks(),monday.plusDays(1)).flatMap{it.sources}.count{it.date==sunday})
    }
    @Test fun thursdayFridaySaturdayRemainUndefined() {
        (3L..5L).forEach {assertTrue(dailyFrozenTemplate(monday.plusDays(it)).isEmpty())}
    }
    @Test fun emptyUnknownDayIsSavedWithoutInventingProcedures()=runBlocking {
        val day=monday.plusDays(3);store.openDaily(day);store.openDaily(day)
        assertTrue(tasks().isEmpty());assertEquals(listOf(day),store.dailyDates.first())
    }
}
