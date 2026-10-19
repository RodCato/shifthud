package com.shifthud.frozen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*

/** Separate test-owned file verifies true process restart without editing user tasks. */
class FrozenRestartDeviceTest {
    @Test fun persistedTaskAcrossProcesses()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("frozenPhase") ?: return@runBlocking
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.filesDir,"frozen-restart-test.preferences_pb")
        val job=SupervisorJob()
        val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
        try {
            if(phase=="write") {
                store.initialize();val task=store.tasks.first().first()
                store.setState(task.id,FrozenState.COMPLETED)
                store.setState(task.id,FrozenState.TODO)
                store.saveDetails(task.id,task.title,task.description,"Restart note: finish Tuesday")
                store.setState(task.id,FrozenState.CARRY_OVER)
            } else {
                store.initialize();val tasks=store.tasks.first()
                assertEquals(10,tasks.size)
                assertEquals(FrozenState.CARRY_OVER,tasks.first().state)
                assertEquals("Restart note: finish Tuesday",tasks.first().notes)
                assertEquals(listOf(FrozenState.COMPLETED,FrozenState.TODO,FrozenState.CARRY_OVER),tasks.first().history.map { it.state })
            }
        } finally { job.cancelAndJoin();if(phase=="read") file.delete() }
    }
}
