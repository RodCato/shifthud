package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.*
import com.shifthud.frozen.*
import com.shifthud.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class FrozenWidgetActionTest {
    private val file=Files.createTempDirectory("task-checkbox").resolve("frozen.preferences_pb").toFile()
    private var job=SupervisorJob()
    private var now=Instant.parse("2026-11-02T18:00:00Z")
    private var zone=ZoneId.of("America/Chicago")
    private val refreshes=AtomicInteger()
    private val clock=object:Clock() {
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)
    }
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}),clock,{zone},{refreshes.incrementAndGet();Unit})
    private var store=create()
    private val today=LocalDate.of(2026,11,2)
    @After fun close()=runBlocking {job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit}
    private suspend fun command(id:String,done:Boolean)=widgetFrozenState(store.tasks.first(),FloatCounts(),today).tasks.single {it.id==id}.let {FrozenTaskCommand(today,it.id,it.revision,done)}
    @Test fun completeReopenPreservesFactsAndRejectsOldDeliveryAfterRestart()=runBlocking {
        store.openDaily(today)
        val first=store.tasks.first().first()
        store.saveDetails(first.id,first.title,first.description,"Keep these notes")
        val before=store.tasks.first().first()
        val action=command(first.id,true)
        val executor=FrozenTaskActionExecutor(store)
        assertTrue(executor.execute(action))
        val done=store.tasks.first().single {it.id==first.id}
        assertEquals(before.copy(state=FrozenState.COMPLETED,history=done.history),done)
        assertEquals(1,done.history.size)
        assertEquals(1,widgetFrozenState(store.tasks.first(),FloatCounts(),today).completed)
        assertTrue(executor.execute(command(first.id,false)))
        assertEquals(2,store.tasks.first().single {it.id==first.id}.history.size)
        job.cancelAndJoin();job=SupervisorJob();store=create()
        assertFalse(FrozenTaskActionExecutor(store).execute(action))
        assertEquals(FrozenState.TODO,store.tasks.first().single {it.id==first.id}.state)
        assertEquals("Keep these notes",store.tasks.first().single {it.id==first.id}.notes)
    }
    @Test fun concurrentDuplicateActionsAppendOneEventAndAppEditWinsOverStaleWidget()=runBlocking {
        store.openDaily(today);val id=store.tasks.first().first().id
        val action=command(id,true)
        val before=refreshes.get()
        val results=coroutineScope {(1..40).map {async(Dispatchers.IO) {FrozenTaskActionExecutor(store).execute(action)}}.awaitAll()}
        assertEquals(1,results.count {it});assertEquals(before+1,refreshes.get())
        val stale=command(id,false)
        store.saveDetails(id,"Changed in app","Description","Notes")
        assertFalse(FrozenTaskActionExecutor(store).execute(stale))
        assertEquals(FrozenState.COMPLETED,store.tasks.first().single {it.id==id}.state)
        store.setDailyState(today,id,FrozenState.TODO)
        assertFalse(widgetFrozenState(store.tasks.first(),FloatCounts(),today).tasks.single {it.id==id}.completed)
    }
    @Test fun groupedCarryoverUsesExistingDailyCompletionRulesWithoutLosingLineage()=runBlocking {
        store.openDaily(today.minusDays(1));store.openDaily(today)
        val row=dailyFrozenRows(store.tasks.first(),today).first {it.sources.size>1}
        val before=store.tasks.first()
        assertTrue(FrozenTaskActionExecutor(store).execute(command(row.task.id,true)))
        val after=store.tasks.first()
        row.sources.forEach {source->
            val updated=after.single {it.id==source.id}
            assertEquals(source.date,updated.date);assertEquals(source.notes,updated.notes)
            assertEquals(source.history,updated.history.dropLast(1));assertEquals(FrozenState.COMPLETED,updated.state)
        }
        assertEquals(before.filter {old->row.sources.none {it.id==old.id}},after.filter {old->row.sources.none {it.id==old.id}})
        val completed=dailyFrozenRows(after,today).first {it.sources.any {source->source.id==row.task.id}}
        assertTrue(FrozenTaskActionExecutor(store).execute(command(completed.task.id,false)))
        assertTrue(dailyFrozenRows(store.tasks.first(),today).any {it.task.state==FrozenState.CARRY_OVER})
    }
    @Test fun staleDateWrongIdAndRejectedActionRefreshWithoutMutatingRecords()=runBlocking {
        store.openDaily(today);val id=store.tasks.first().first().id
        val action=command(id,true);val before=store.tasks.first();var redraws=0
        val executor=FrozenTaskActionExecutor(store) {redraws++}
        assertFalse(executor.execute(action.copy(id="missing")))
        assertFalse(executor.execute(action.copy(revision="bad")))
        now=now.plusSeconds(86400)
        assertFalse(executor.execute(action))
        assertEquals(before,store.tasks.first());assertEquals(3,redraws)
        assertEquals(FloatCounts(),store.floats.first())
    }
}
