package com.shifthud.frozen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class FrozenTuesdayRestartDeviceTest {
    @Test fun workflowAndPickedProductsSurviveNewProcess()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("tuesdayPhase") ?: return@runBlocking
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.filesDir,"tuesday-restart-test.preferences_pb")
        val job=SupervisorJob()
        val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
        val date=LocalDate.of(2026,10,20)
        try {
            store.initialize()
            if(phase=="write") {
                val task=store.tasks.first().first()
                store.saveDetails(task.id,task.title,task.description,"Monday origin, Tuesday completion")
                store.setState(task.id,FrozenState.CARRY_OVER)
                store.startTuesday(date);store.setState(task.id,FrozenState.COMPLETED)
                store.importProducts(date,date.minusDays(3),"Aisle 10 Front — Center",listOf(EndcapProduct("Synthetic Cheese Pizza","12345")))
                store.setState(store.tasks.first().single { it.plan.isNotBlank() }.id,FrozenState.COMPLETED)
            } else {
                val before=store.tasks.first();store.startTuesday(date)
                assertEquals(before,store.tasks.first());assertEquals(31,before.size)
                assertEquals(date.toString(),store.selectedWorkflow.first())
                assertEquals(FROZEN_MONDAY,before.first().date)
                assertEquals("Monday origin, Tuesday completion",before.first().notes)
                assertEquals(listOf(FrozenState.CARRY_OVER,FrozenState.COMPLETED),before.first().history.map { it.state })
                assertEquals(FrozenState.COMPLETED,before.single { it.plan.isNotBlank() }.state)
                assertTrue(before.filter { it.section.contains("FINAL FILL") }.all { it.state==FrozenState.TODO })
            }
        } finally { job.cancelAndJoin();if(phase=="read")file.delete() }
    }
}
