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
class PublixProChecklistTest {
    private val file=Files.createTempDirectory("daily-frozen").resolve("tasks.preferences_pb").toFile()
    private var job=SupervisorJob()
    private var scheduled=setOf(LocalDate.of(2026,11,16),LocalDate.of(2026,11,10),LocalDate.of(2026,11,11),LocalDate.of(2026,11,13),LocalDate.of(2026,11,15))
    private var now=Instant.parse("2026-11-16T18:00:00Z")
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}).also { dataStore=it },Clock.fixed(now,ZoneOffset.UTC),scheduledDates={scheduled})
    private var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>?=null
    private var store=create()
    private val monday=LocalDate.of(2026,11,16)
    @After fun close()=runBlocking {job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit}
    private suspend fun tasks()=store.tasks.first()
    @Test fun dailyChecksAreIndependentOrderedAndDoNotDuplicate()=runBlocking {
        scheduled=(0L..6L).map {monday.plusDays(it)}.toSet()
        store.openDaily(monday)
        val rows=dailyFrozenRows(tasks(),monday).map {it.task}
        assertEquals("Check freezer and ad wall",rows.first().title)
        assertEquals("Check Publix Pro Store Lists",rows[1].title)
        assertEquals(rows.indexOfFirst {it.title=="Perform Random Counts"}+1,rows.indexOfFirst {it.opportunityBased})
        assertTrue(rows.indexOfFirst {it.id.startsWith("pro-afternoon-")}>rows.size/2)
        val before=tasks();repeat(3) {store.openDaily(monday)};assertEquals(before,tasks())
        for(offset in listOf(1L,2L,4L,6L)) {
            val day=monday.plusDays(offset);store.openDaily(day)
            val own=tasks().filter {it.date==day}
            assertEquals(1,own.count {it.id.startsWith("pro-morning-")})
            assertEquals(1,own.count {it.id.startsWith("pro-afternoon-")})
            assertEquals(1,own.count {it.opportunityBased});assertEquals(1,own.count {it.optionalTraining})
        }
    }
    @Test fun opportunityDoesNotCarryAndChecksShareWidgetCompletionHistory()=runBlocking {
        scheduled=setOf(monday,monday.plusDays(1));store.openDaily(monday)
        val sims=tasks().single {it.opportunityBased}
        assertFalse(dailyFrozenRows(tasks(),monday.plusDays(1)).any {it.task.id==sims.id})
        for(key in listOf("pro-morning-","pro-afternoon-")) {
            val rendered=com.shifthud.widget.widgetFrozenState(tasks(),FloatCounts(),monday).tasks.single {it.id.startsWith(key)}
            assertTrue(com.shifthud.widget.FrozenTaskActionExecutor(store).execute(com.shifthud.widget.FrozenTaskCommand(monday,rendered.id,rendered.revision,true)))
            assertEquals(1,tasks().single {it.id==rendered.id}.history.size)
        }
        assertEquals(2,tasks().count {it.state==FrozenState.COMPLETED})
        store.setDailyState(monday,sims.id,FrozenState.COMPLETED)
        val before=tasks();job.cancelAndJoin();job=SupervisorJob();store=create()
        assertEquals(before,tasks())
        store.openDaily(monday.plusDays(1))
        assertFalse(dailyFrozenRows(tasks(),monday.plusDays(1)).any {it.sources.any {source->source.id==sims.id}})
        assertTrue(dailyFrozenRows(tasks(),monday.plusDays(1)).any {it.task.state==FrozenState.CARRY_OVER})
        assertEquals(FrozenState.COMPLETED,tasks().single {it.id==sims.id}.state)
    }
}
