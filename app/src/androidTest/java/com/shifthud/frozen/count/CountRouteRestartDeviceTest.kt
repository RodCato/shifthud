package com.shifthud.frozen.count

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class CountRouteRestartDeviceTest {
    @Test fun partialDoorSurvivesProcessAndDeviceRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("countPhase") ?: return@runBlocking
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,"count-route-restart-test.preferences_pb")
        val job=SupervisorJob();val store=CountRouteStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
        suspend fun data()=store.data.first()
        try {
            if(phase=="write") {
                store.saveMap(0,initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=null,sections=listOf(PhysicalSection("test",department=SectionDepartment.FROZEN,kind=SectionKind.UPRIGHT,count=5))) else s.copy(excluded=true)})
                store.start(data().revision)
                repeat(4) {store.resolve(data().revision,1,data().active!!.current!!,DoorState.SAMPLED)}
                repeat(3) {store.count(data().revision,1,"A10-R-05")}
                store.pause(data().revision,1)
                store.submission(data().revision,1,data().active!!.sessions.single().id,Submission.AWAITING,"Synthetic restart note")
                store.saveMap(data().revision,data().map.map {if(it.id=="A10-R")it.copy(count=null,sections=listOf(PhysicalSection("test",department=SectionDepartment.FROZEN,kind=SectionKind.UPRIGHT,count=4))) else it})
            } else {
                val d=data();val c=d.active!!
                assertEquals("A10-R-05",c.current);assertEquals(3,c.currentDoor!!.products);assertEquals(5,c.target);assertTrue(c.paused)
                assertEquals(4,c.sampled);assertEquals(5,c.doors.size);assertEquals(4,d.map.first().sections!!.single().count)
                assertEquals(Submission.AWAITING,c.sessions.single().submission);assertEquals("Synthetic restart note",c.sessions.single().note)
                assertNotNull(c.sessions.single().ended)
                if(phase=="reboot") {
                    store.resume(d.revision,1);assertEquals("A10-R-05",data().active!!.current)
                    store.resolve(data().revision,1,"A10-R-05",DoorState.SAMPLED);store.finish(data().revision,1)
                    val history=data().cycles.single();store.start(data().revision)
                    assertEquals(history,data().cycles.first());assertEquals(4,data().active!!.doors.size)
                }
            }
        } finally {job.cancelAndJoin();if(phase=="reboot")file.delete()}
    }
}
