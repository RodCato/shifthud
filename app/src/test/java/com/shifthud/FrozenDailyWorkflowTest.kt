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
        val monday=LocalDate.of(2026,11,9)
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
        before.forEach { old->val now=tasks().single{it.id==old.id};assertEquals(old.copy(workflows=now.workflows,carryoverArchived=old.date<FROZEN_CARRYOVER_BASELINE && old.state!=FrozenState.COMPLETED),now) }
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
    @Test fun thursdaySaturdayRemainUndefined() {
        listOf(3L,5L).forEach {assertTrue(dailyFrozenTemplate(monday.plusDays(it)).isEmpty())}
    }
    private suspend fun seed(saved:List<FrozenTask>,visited:LocalDate?=null) {
        dataStore!!.edit { values ->
            values[androidx.datastore.preferences.core.stringPreferencesKey("tasks_v1")]=store.encode(saved)
            if(visited!=null) values[androidx.datastore.preferences.core.stringSetPreferencesKey("daily_workflow_dates")]=setOf(visited.toString())
        }
    }
    @Test fun sevenOctoberCarryoversArchivedOnceAndSourceFactsSurviveRestart()=runBlocking {
        val sunday=FROZEN_CARRYOVER_BASELINE
        // Representative geometry-free fixture: seven unresolved sources from the reported dates.
        val old=(mondayFrozenTemplate().take(4)+wednesdayFrozenTemplate(LocalDate.of(2026,10,21)).take(3))
            .map { it.copy(state=FrozenState.CARRY_OVER,notes="Synthetic historical note",history=listOf(FrozenEvent(FrozenState.CARRY_OVER,Instant.parse("2026-10-22T12:00:00Z"))),workflows=it.workflows+dailyWorkflowId(sunday)) }
        val completed=mondayFrozenTemplate()[4].copy(state=FrozenState.COMPLETED)
        val edited=sundayFrozenTemplate(sunday).first().copy(title="Reviewed title",notes="Keep Sunday edit")
        seed(old+completed+edited,sunday)
        store.openDaily(sunday)
        old.forEach { previous ->assertEquals(previous.copy(carryoverArchived=true),tasks().single{it.id==previous.id}) }
        assertEquals(completed,tasks().single{it.id==completed.id})
        assertEquals(edited,tasks().single{it.id==edited.id})
        assertEquals(7,dailyFrozenRows(tasks(),sunday).size)
        assertTrue(dailyFrozenRows(tasks(),sunday).all{it.task.state==FrozenState.TODO})
        assertTrue(dataStore!!.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey("carryovers_archived_before_2026_11_01")] == true)
        val before=tasks();job.cancelAndJoin();job=SupervisorJob();store=create()
        repeat(3){store.openDaily(sunday)};assertEquals(before,tasks())
        assertTrue(frozenWorkflowTasks(tasks(),"").any{it.id==old.first().id})
        store.setState(old.first().id,FrozenState.IN_PROGRESS)
        store.openDaily(monday)
        assertTrue(tasks().single{it.id==old.first().id}.carryoverArchived)
        assertTrue(dailyFrozenRows(tasks(),monday).flatMap{it.sources}.none{it.date<FROZEN_CARRYOVER_BASELINE})
    }
    @Test fun cutoffAlsoProtectsAgainstLateHistoricalInitialization()=runBlocking {
        store.openDaily(FROZEN_CARRYOVER_BASELINE)
        store.openDaily(LocalDate.of(2026,10,25))
        assertTrue(tasks().filter{it.date<FROZEN_CARRYOVER_BASELINE}.all{it.carryoverArchived})
        store.openDaily(monday)
        assertTrue(dailyFrozenRows(tasks(),monday).flatMap{it.sources}.all{it.date>=FROZEN_CARRYOVER_BASELINE})
    }
    @Test fun everyConfiguredWeekdayRepairsCarryoverOnlyOrManualOnlyDay()=runBlocking {
        for(offset in 0L..3L) {
            val day=FROZEN_CARRYOVER_BASELINE.plusDays(offset)
            store.openDaily(day)
            assertTrue(tasks().map{it.id}.containsAll(dailyFrozenTemplate(day).map{it.id}))
        }
        val nextMonday=monday.plusWeeks(1)
        val custom=FrozenTask("manual-repair",nextMonday,"Synthetic manual task","Saved description",notes="Saved note")
        seed(tasks()+custom,nextMonday);store.openDaily(nextMonday)
        assertEquals(custom.copy(workflows=listOf(dailyWorkflowId(nextMonday))),tasks().single{it.id==custom.id})
        assertTrue(tasks().map{it.id}.containsAll(dailyFrozenTemplate(nextMonday).map{it.id}))
    }
    @Test fun dismissManualTaskPreservesFactsAndCannotReturnAfterRestart()=runBlocking {
        val sunday=FROZEN_CARRYOVER_BASELINE
        store.openDaily(sunday);store.add("Synthetic freight","Reference","Unfinished note")
        val original=tasks().last()
        store.dismissDaily(sunday,original.id)
        assertEquals(original.copy(carryoverExcluded=true),tasks().last())
        assertFalse(dailyFrozenRows(tasks(),sunday).any{it.task.id==original.id})
        job.cancelAndJoin();job=SupervisorJob();store=create();store.openDaily(monday)
        assertFalse(dailyFrozenRows(tasks(),monday).any{row->row.sources.any{it.id==original.id}})
        assertTrue(frozenWorkflowTasks(tasks(),dailyWorkflowId(sunday)).any{it.id==original.id})
    }
    @Test fun resetMarkerDoesNotReapplyToLaterHistoricalEdits()=runBlocking {
        store.openDaily(FROZEN_CARRYOVER_BASELINE)
        val old=FrozenTask("historical-later",LocalDate.of(2026,10,21),"Historical correction","Reference")
        seed(tasks()+old);store.openDaily(monday)
        assertEquals(old,tasks().single{it.id==old.id})
        assertTrue(dailyFrozenRows(tasks(),monday).flatMap{it.sources}.none{it.id==old.id})
    }
    @Test fun fridayRepairsEarlierUndefinedDateWithoutChangingManualNotes()=runBlocking {
        val friday=LocalDate.of(2026,11,6)
        val manual=FrozenTask("manual-friday",friday,"Synthetic freight","Already verified",notes="Approximate quantity noted")
        seed(listOf(manual),friday)
        dataStore!!.edit {it[androidx.datastore.preferences.core.stringSetPreferencesKey("daily_template_dates_v1")]=setOf(friday.toString())}
        store.openDaily(friday)
        assertEquals(9,tasks().size)
        assertEquals(manual.copy(workflows=listOf(dailyWorkflowId(friday))),tasks().first())
        assertTrue(tasks().all{it.state==FrozenState.TODO})
        val before=tasks();store.openDaily(friday);assertEquals(before,tasks())
    }
    @Test fun fridayFreightSurvivesSaturdayAndSundayWithoutCopies()=runBlocking {
        val friday=LocalDate.of(2026,11,6);store.openDaily(friday)
        store.add("Synthetic unfinished freight","Origin: prior vendor delivery","Remaining: approximately half a pallet; verified")
        val original=tasks().last()
        store.openDaily(friday.plusDays(1));store.openDaily(friday.plusDays(2));store.openDaily(friday.plusDays(2))
        assertEquals(1,tasks().count{it.id==original.id})
        val carried=dailyFrozenRows(tasks(),friday.plusDays(2)).single{it.task.id==original.id}
        assertEquals(friday,carried.task.date);assertEquals(original.notes,carried.task.notes)
        assertEquals(FrozenState.CARRY_OVER,carried.task.state)
        assertTrue(tasks().none{it.state==FrozenState.COMPLETED})
    }
    @Test fun resolutionByAnotherAssociatePreservesNotesAndNeverRecordsPersonalCompletion()=runBlocking {
        val friday=LocalDate.of(2026,11,6);store.openDaily(friday)
        val original=tasks().first()
        store.saveDetails(original.id,original.title,original.description,"Thursday KE; already verified; awaiting stocking")
        val saved=tasks().first();store.openDaily(friday.plusDays(2))
        store.resolveDailyByOther(friday.plusDays(2),original.id)
        val resolved=tasks().single{it.id==original.id}
        assertNotNull(resolved.resolvedByOtherAt)
        assertEquals(saved.state,resolved.state);assertEquals(saved.history,resolved.history);assertEquals(saved.notes,resolved.notes)
        assertFalse(dailyFrozenRows(tasks(),friday.plusDays(2)).any{row->row.sources.any{it.id==original.id}})
        job.cancelAndJoin();job=SupervisorJob();store=create();store.openDaily(friday.plusDays(3))
        assertEquals(resolved.resolvedByOtherAt,tasks().single{it.id==original.id}.resolvedByOtherAt)
        assertFalse(dailyFrozenRows(tasks(),friday.plusDays(3)).any{row->row.sources.any{it.id==original.id}})
        assertTrue(frozenWorkflowTasks(tasks(),dailyWorkflowId(friday)).any{it.id==original.id})
    }
    @Test fun repeatedFuturePlanningDoesNotPersistTasksOrChangeSelectedWorkflow()=runBlocking {
        store.openDaily(monday)
        val before=tasks();val dates=store.dailyDates.first();val selected=store.selectedWorkflow.first()
        repeat(3) {frozenPlanningWeek(tasks(),emptyList(),monday.plusWeeks(3),monday)}
        assertEquals(before,tasks());assertEquals(dates,store.dailyDates.first());assertEquals(selected,store.selectedWorkflow.first())
        val friday=monday.plusDays(4)
        val projected=frozenPlanningRows(tasks(),friday,monday)
        store.openDaily(friday)
        assertEquals(projected.map{it.task.id}.toSet(),dailyFrozenRows(tasks(),friday).map{it.task.id}.toSet())
        assertEquals(8,tasks().count{it.date==friday})
    }
    @Test fun emptyUnknownDayIsSavedWithoutInventingProcedures()=runBlocking {
        val day=monday.plusDays(3);store.openDaily(day);store.openDaily(day)
        assertTrue(tasks().isEmpty());assertEquals(listOf(day),store.dailyDates.first())
    }
}
