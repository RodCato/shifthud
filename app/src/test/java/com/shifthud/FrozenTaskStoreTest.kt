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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class FrozenTaskStoreTest {
    private val file=Files.createTempDirectory("frozen-test").resolve("tasks.preferences_pb").toFile()
    private var job=SupervisorJob()
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}),Clock.fixed(Instant.parse("2026-10-19T15:00:00Z"),ZoneOffset.UTC))
    private var store=create()
    @After fun cleanup()=runBlocking { job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit }
    @Test fun initializationIsAtomicIdempotentAndOneTime()=runBlocking {
        coroutineScope { repeat(4) { launch { store.initialize() } } }
        val tasks=store.tasks.first()
        assertEquals(10,tasks.size);assertEquals(10,tasks.map { it.id }.distinct().size)
        assertTrue(tasks.all { it.date==LocalDate.of(2026,10,19) && it.state==FrozenState.TODO })
        store.saveDetails(tasks.first().id,"Edited template task","My reference","My note")
        store.initialize()
        assertEquals("Edited template task",store.tasks.first().first().title)
    }
    @Test fun completionReopeningAndRepeatedUpdatesKeepHistory()=runBlocking {
        store.initialize();val id=store.tasks.first().first().id
        store.setState(id,FrozenState.COMPLETED);store.setState(id,FrozenState.COMPLETED)
        store.setState(id,FrozenState.TODO)
        val task=store.tasks.first().first()
        assertEquals(FrozenState.TODO,task.state)
        assertEquals(listOf(FrozenState.COMPLETED,FrozenState.TODO),task.history.map { it.state })
    }
    @Test fun blockedNotesAndCarryoverSurviveReopeningStore()=runBlocking {
        store.initialize();val original=store.tasks.first().first()
        store.setState(original.id,FrozenState.IN_PROGRESS)
        store.setState(original.id,FrozenState.BLOCKED)
        store.saveDetails(original.id,original.title,original.description,"Scanner unavailable. Finish Tuesday.")
        store.setState(original.id,FrozenState.CARRY_OVER)
        val expected=store.tasks.first()
        job.cancelAndJoin();job=SupervisorJob();store=create();store.initialize()
        assertEquals(expected,store.tasks.first())
        val restored=store.tasks.first().first()
        assertEquals(original.id,restored.id);assertEquals(original.date,restored.date)
        assertEquals(FrozenState.CARRY_OVER,restored.state)
        assertEquals("Scanner unavailable. Finish Tuesday.",restored.notes)
        assertEquals(10,store.tasks.first().size)
    }
    @Test fun editingDetailsDoesNotOverwriteNewStateAndAddingDoesNotResetTemplate()=runBlocking {
        store.initialize();val stale=store.tasks.first().first()
        store.setState(stale.id,FrozenState.COMPLETED)
        store.saveDetails(stale.id,"Updated",stale.description,"Retain completion")
        assertEquals(FrozenState.COMPLETED,store.tasks.first().first().state)
        store.add("Extra handoff","Ask manager","Unfinished")
        store.initialize();assertEquals(11,store.tasks.first().size)
    }
    @Test fun invalidEditsLeaveSavedDataIntact()=runBlocking {
        store.initialize();val tasks=store.tasks.first()
        assertTrue(runCatching { store.saveDetails(tasks.first().id," ","","") }.isFailure)
        assertTrue(runCatching { store.saveDetails(tasks.first().id,"Title","","x".repeat(1001)) }.isFailure)
        assertTrue(runCatching { store.setState("missing",FrozenState.COMPLETED) }.isFailure)
        assertEquals(tasks,store.tasks.first())
    }
    @Test fun corruptDataIsNotReplacedBySeed()=runBlocking {
        job.cancelAndJoin();job=SupervisorJob()
        val raw=PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})
        raw.edit { it[stringPreferencesKey("tasks_v1")]="not valid json" }
        store=FrozenTaskStore(raw)
        assertTrue(runCatching { store.initialize() }.isFailure)
        assertEquals("not valid json",raw.data.first()[stringPreferencesKey("tasks_v1")])
    }
    @Test fun templateReferencesArePersonalAndDoNotRequireClockOrWeekInputs() {
        assertTrue(FROZEN_REFERENCE_NOTE.contains("not official"))
        val template=mondayFrozenTemplate()
        assertEquals(template,mondayFrozenTemplate())
        assertTrue(template.single { it.title=="Review MASC" }.description.contains("only when authorized"))
        assertTrue(template.single { it.title=="Process donations" }.description.contains("meat-freezer"))
    }
}
