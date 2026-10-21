package com.shifthud.frozen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class EndcapFamilyRestartDeviceTest {
    @Test fun reviewedFamilyAndChosenVarietiesSurviveProcessRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("familyPhase") ?: return@runBlocking
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.filesDir,"endcap-family-restart-test.preferences_pb")
        val job=SupervisorJob();val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
        val date=LocalDate.of(2026,10,20)
        val rows=listOf(EndcapProduct("Vanilla corrected","12345","Gelato","Desserts — Right"),EndcapProduct("Mango","12346","Gelato","Desserts — Right"))
        try {
            store.initialize()
            if(phase=="write") {
                store.startTuesday(date);store.startWednesday(date.plusDays(1));store.setTruckArrived(date.plusDays(1),true)
                store.importProducts(date,date.minusDays(3),TARGET_ENDCAP,rows)
                val family=store.tasks.first().single {it.family.isNotBlank()}
                store.chooseEndcapVariety(family.id,rows.first(),true);store.setState(family.id,FrozenState.COMPLETED)
                store.saveDetails(family.id,"My desserts",family.description,"Keep corrected family note")
            } else {
                val before=store.tasks.first();val truck=store.truckWorkflows.first()
                val family=before.single {it.family.isNotBlank()}
                assertEquals("My desserts",family.title);assertEquals("Keep corrected family note",family.notes)
                assertEquals(FrozenState.COMPLETED,family.state);assertEquals(rows,family.eligible)
                assertEquals(listOf(endcapProductIdentity(rows.first())),family.chosen)
                store.importProducts(date,date.minusDays(3),TARGET_ENDCAP,rows)
                assertEquals(before,store.tasks.first());assertEquals(truck,store.truckWorkflows.first())
                assertTrue(before.filter {it.section.contains("FINAL FILL")}.all {it.state==FrozenState.TODO})
            }
        } finally {job.cancelAndJoin();if(phase=="read")file.delete()}
    }
}
