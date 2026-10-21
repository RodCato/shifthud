package com.shifthud.frozen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.time.*

/** Isolated file: never replaces or restores production checklist data. */
class FrozenWednesdayRestartDeviceTest {
    @Test fun truckArrivalAndOriginalCarryoverSurviveProcessRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("wednesdayPhase") ?: return@runBlocking
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.filesDir,"wednesday-restart-test.preferences_pb")
        val job=SupervisorJob()
        val instant=Instant.parse("2026-10-21T18:17:00Z")
        val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}),Clock.fixed(instant,ZoneOffset.UTC))
        val date=LocalDate.of(2026,10,21)
        try {
            store.initialize()
            if(phase=="write") {
                store.startTuesday(date.minusDays(1))
                store.setState("frozen-2026-10-19-7",FrozenState.CARRY_OVER)
                store.saveDetails("frozen-2026-10-19-7","Counts follow-up","Reference","Original Monday note")
                store.startWednesday(date)
                store.setState("frozen-2026-10-19-7",FrozenState.COMPLETED)
                store.setTruckArrived(date,true,ZoneId.of("America/Chicago"))
            } else {
                val tasks=store.tasks.first();val trucks=store.truckWorkflows.first()
                store.startWednesday(date)
                assertEquals(tasks,store.tasks.first());assertEquals(trucks,store.truckWorkflows.first())
                assertEquals(wednesdayWorkflowId(date),store.selectedWorkflow.first())
                assertEquals(instant,trucks.single().arrival!!.at);assertEquals("America/Chicago",trucks.single().arrival!!.zone)
                val carried=tasks.single { it.id=="frozen-2026-10-19-7" }
                assertEquals(FROZEN_MONDAY,carried.date);assertEquals("Original Monday note",carried.notes)
                assertEquals(listOf(FrozenState.CARRY_OVER,FrozenState.COMPLETED),carried.history.map { it.state })
                assertFalse(tasks.any { it.id=="wednesday-$date-random-counts" })
                assertEquals(20,tasks.count { it.date==date.minusDays(1) })
            }
        } finally { job.cancelAndJoin();if(phase=="read")file.delete() }
    }
}
