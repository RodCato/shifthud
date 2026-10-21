package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.*
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class FrozenWednesdayTest {
    private val date=LocalDate.of(2026,10,21)
    private val id=wednesdayWorkflowId(date)
    private val instant=Instant.parse("2026-10-21T18:17:00Z")
    private val zone=ZoneId.of("America/Chicago")
    private val file=Files.createTempDirectory("wednesday-test").resolve("tasks.preferences_pb").toFile()
    private var job=SupervisorJob()
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}),Clock.fixed(instant,zone))
    private var store=create()
    @After fun cleanup()=runBlocking { job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit }
    @Test fun initializationIsExplicitAtomicAndIdempotent()=runBlocking {
        store.initialize();assertTrue(store.truckWorkflows.first().isEmpty());assertEquals(10,store.tasks.first().size)
        coroutineScope { repeat(4) { launch { store.startWednesday(date) } } }
        assertEquals(29,store.tasks.first().size);assertEquals(29,store.tasks.first().map { it.id }.distinct().size)
        assertEquals(id,store.selectedWorkflow.first())
        val record=store.truckWorkflows.first().single();assertEquals(date,record.date);assertNull(record.arrival);assertTrue(record.events.isEmpty())
        assertEquals(19,record.taskIds.size)
        assertEquals(listOf(6,4,5,4),truckSectionOrder(false).map { section -> frozenWorkflowTasks(store.tasks.first(),id).count { it.section==section } })
    }
    @Test fun mondayTuesdayHistoryAndCarryoverIdentityArePreserved()=runBlocking {
        store.initialize();val monday=store.tasks.first()
        store.setState(monday[0].id,FrozenState.COMPLETED)
        store.setState(monday[7].id,FrozenState.CARRY_OVER)
        store.saveDetails(monday[7].id,monday[7].title,monday[7].description,"Scanner needed")
        store.startTuesday(date.minusDays(1))
        val tuesday=store.tasks.first().first { it.date==date.minusDays(1) }
        store.setState(tuesday.id,FrozenState.COMPLETED)
        val before=store.tasks.first()
        store.startWednesday(date)
        before.filterNot { it.state==FrozenState.CARRY_OVER }.forEach { old->assertEquals(old,store.tasks.first().single { it.id==old.id }) }
        val carried=frozenWorkflowTasks(store.tasks.first(),id).single { it.id==monday[7].id }
        assertEquals(FROZEN_MONDAY,carried.date);assertEquals("Scanner needed",carried.notes)
        store.setState(carried.id,FrozenState.COMPLETED)
        val completed=frozenWorkflowTasks(store.tasks.first(),id).single { it.id==carried.id }
        assertEquals(carried.history,completed.history.dropLast(1));assertEquals(instant,completed.history.last().at)
        assertEquals(date,completed.history.last().at.atZone(zone).toLocalDate())
        assertFalse(groupFrozenTasks(frozenWorkflowTasks(store.tasks.first(),id)).carryovers.any { it.id==carried.id })
        assertEquals(FROZEN_MONDAY,completed.date)
    }
    @Test fun stableTypeReusesUnfinishedCountsButNeverTitleMatchesCustomWork()=runBlocking {
        store.initialize();val counts=store.tasks.first().single { it.id=="frozen-2026-10-19-7" }
        store.saveDetails(counts.id,"Counts follow-up","Original count reference","Keep this note")
        store.setState(counts.id,FrozenState.CARRY_OVER)
        store.add("Perform Random Counts","Different custom work","Distinct")
        val custom=store.tasks.first().last();store.setState(custom.id,FrozenState.CARRY_OVER)
        store.startWednesday(date)
        val visible=frozenWorkflowTasks(store.tasks.first(),id)
        assertTrue(visible.any { it.id==counts.id });assertTrue(visible.any { it.id==custom.id })
        assertFalse(visible.any { it.id=="wednesday-$date-random-counts" })
        store.setState(counts.id,FrozenState.COMPLETED)
        val expected=store.tasks.first();store.startWednesday(date)
        assertEquals(expected,store.tasks.first()) // Completing an alias never regenerates its skipped template step.
        assertEquals("Keep this note",store.tasks.first().single { it.id==counts.id }.notes)
    }
    @Test fun completedRecurringWorkGetsNewOccurrenceAndHistoricalTruckIsNotReset()=runBlocking {
        store.initialize();store.setState("frozen-2026-10-19-4",FrozenState.COMPLETED)
        store.startWednesday(date)
        assertTrue(store.tasks.first().any { it.id=="wednesday-$date-replenish-ad-wall" })
        store.setTruckArrived(date,true,zone)
        val first=store.truckWorkflows.first().single()
        store.startWednesday(date.plusWeeks(1))
        assertEquals(first,store.truckWorkflows.first().single { it.date==date })
        assertNull(store.truckWorkflows.first().single { it.date==date.plusWeeks(1) }.arrival)
        assertTrue(store.tasks.first().any { it.id=="wednesday-${date.plusWeeks(1)}-replenish-ad-wall" })
    }
    @Test fun arrivalOnlyChangesEventStateAndUndoKeepsHistory()=runBlocking {
        store.initialize();store.startWednesday(date)
        val tasks=store.tasks.first()
        store.setTruckArrived(date,true,zone);store.setTruckArrived(date,true,zone)
        val record=store.truckWorkflows.first().single()
        assertEquals(listOf(TruckArrivalEvent(true,instant,zone.id)),record.events)
        assertEquals("1:17 PM",truckArrivalText(record.arrival!!,Locale.US,false))
        assertEquals("13:17",truckArrivalText(record.arrival!!,Locale.US,true))
        assertEquals(listOf("TRUCK RECEIVING","STOCKING"),truckSectionOrder(true).take(2))
        assertEquals(tasks,store.tasks.first())
        store.setTruckArrived(date,false,zone);store.setTruckArrived(date,false,zone)
        val undone=store.truckWorkflows.first().single();assertNull(undone.arrival)
        assertEquals(listOf(true,false),undone.events.map { it.arrived });assertEquals(tasks,store.tasks.first())
        store.setTruckArrived(date,true,zone);assertEquals(3,store.truckWorkflows.first().single().events.size)
    }
    @Test fun waitingNeverLocksAnyTaskAndProgressCanReopenBlockOrCarry()=runBlocking {
        store.initialize();store.startWednesday(date)
        for(section in truckSectionOrder(false)) {
            val task=store.tasks.first().first { it.section==section }
            store.setState(task.id,FrozenState.COMPLETED);store.setState(task.id,FrozenState.TODO)
            store.setState(task.id,FrozenState.IN_PROGRESS);store.setState(task.id,FrozenState.BLOCKED)
            store.saveDetails(task.id,task.title,task.description,"Need equipment")
            store.setState(task.id,FrozenState.CARRY_OVER)
        }
        assertNull(store.truckWorkflows.first().single().arrival)
        val carried=store.tasks.first().filter { it.state==FrozenState.CARRY_OVER }
        store.startWednesday(date.plusWeeks(1))
        carried.forEach { task ->
            val same=store.tasks.first().single { it.id==task.id }
            assertEquals(task.date,same.date);assertEquals(task.history,same.history);assertEquals(task.notes,same.notes)
        }
        assertEquals(19,frozenWorkflowTasks(store.tasks.first(),wednesdayWorkflowId(date.plusWeeks(1))).size)
    }
    @Test fun manualDatesAndSameDateTuesdayRemainDistinctAndFutureCarryoversAreExcluded()=runBlocking {
        store.initialize();store.startTuesday(date)
        val tuesday=store.tasks.first();store.startWednesday(date)
        tuesday.forEach { old->assertEquals(old,store.tasks.first().single { it.id==old.id }) }
        assertEquals(20,frozenWorkflowTasks(store.tasks.first(),date.toString()).size)
        assertEquals(19,frozenWorkflowTasks(store.tasks.first(),id).size)
        store.startWednesday(date.plusDays(2));val future=store.tasks.first().last();store.setState(future.id,FrozenState.CARRY_OVER)
        store.startWednesday(date)
        assertFalse(frozenWorkflowTasks(store.tasks.first(),id).any { it.id==future.id })
        assertEquals(date,frozenWorkflowDate(id))
    }
    @Test fun processStyleRestartPreservesAssignmentEventsNotesAndAliases()=runBlocking {
        store.initialize();store.setState("frozen-2026-10-19-7",FrozenState.CARRY_OVER)
        store.startWednesday(date);store.setState("frozen-2026-10-19-7",FrozenState.COMPLETED)
        store.add("Custom Wednesday task","Personal reference","Handoff")
        store.setTruckArrived(date,true,zone)
        val tasks=store.tasks.first();val trucks=store.truckWorkflows.first()
        job.cancelAndJoin();job=SupervisorJob();store=create();store.initialize()
        assertEquals(tasks,store.tasks.first());assertEquals(trucks,store.truckWorkflows.first());assertEquals(id,store.selectedWorkflow.first())
        store.startWednesday(date);assertEquals(tasks,store.tasks.first())
        assertEquals(date,tasks.last().date);assertEquals("BEFORE TRUCK",tasks.last().section)
    }
    @Test fun existingDataNeedsNoTruckMigrationAndCorruptionNeverResetsIt()=runBlocking {
        store.initialize();store.startTuesday(date.minusDays(1))
        val tasks=store.tasks.first();assertTrue(store.truckWorkflows.first().isEmpty())
        store.initialize();assertEquals(tasks,store.tasks.first())
        try { store.decodeTrucks("{bad");fail() } catch(_:Exception) { }
        try { store.decodeTrucks("""{"version":2,"workflows":[]}""");fail() } catch(_:IllegalStateException) { }
        try { store.setTruckArrived(date,true,zone);fail() } catch(_:IllegalStateException) { }
        assertEquals(tasks,store.tasks.first())
    }
}
