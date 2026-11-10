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
class FrozenChecklistRefinementsTest {
    private val file=Files.createTempDirectory("daily-frozen").resolve("tasks.preferences_pb").toFile()
    private var job=SupervisorJob()
    private var scheduled=setOf(LocalDate.of(2026,11,9),LocalDate.of(2026,11,10),LocalDate.of(2026,11,11),LocalDate.of(2026,11,13),LocalDate.of(2026,11,15))
    private var now=Instant.parse("2026-11-09T18:00:00Z")
    private fun create()=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}).also { dataStore=it },Clock.fixed(now,ZoneOffset.UTC),scheduledDates={scheduled})
    private var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>?=null
    private var store=create()
    private val monday=LocalDate.of(2026,11,9)
    @After fun close()=runBlocking {job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit}
    private suspend fun tasks()=store.tasks.first()
    @Test fun scheduledTemplatesIncludeOnlyTheirRelevantGuidanceAndOptionalTraining()=runBlocking {
        for(date in scheduled.sorted()) {
            store.openDaily(date)
            val own=tasks().filter {it.date==date}
            assertEquals(1,own.count {it.optionalTraining})
            assertEquals("Check Available CBTs / GRS Training",own.single {it.optionalTraining}.title)
            assertEquals("OPTIONAL · LOW PRIORITY",own.single {it.optionalTraining}.section)
            assertEquals(if(date.dayOfWeek==DayOfWeek.MONDAY)1 else 0,own.count {it.id.startsWith("outgoing-endcap-")})
            assertEquals(if(date.dayOfWeek in listOf(DayOfWeek.MONDAY,DayOfWeek.TUESDAY))1 else 0,own.count {it.id.startsWith("endcap-first-")})
            repeat(3) {store.openDaily(date)}
            assertEquals(own,tasks().filter {it.date==date})
        }
        assertEquals(13,tasks().count {it.date==monday})
        assertEquals(mondayFrozenTemplate().map {it.title},tasks().filter {it.date==monday}.take(10).map {it.title})
        val reset=tasks().single {it.date==monday.plusDays(1) && it.title=="Get cart and clear previous endcap"}
        assertTrue(reset.description.contains("home shelf"))
        assertTrue(tasks().any {it.title=="Build the three-door, five-shelf layout"})
    }
    @Test fun optionalNeverCarriesButCompletionAndWidgetStateSurviveRestart()=runBlocking {
        store.openDaily(monday)
        val optional=tasks().single {it.optionalTraining}
        assertFalse(dailyFrozenRows(tasks(),monday.plusDays(1)).any {it.task.id==optional.id})
        val rendered=com.shifthud.widget.widgetFrozenState(tasks(),FloatCounts(),monday).tasks.single {it.id==optional.id}
        assertTrue(rendered.displayTitle.endsWith("Optional"))
        assertTrue(com.shifthud.widget.FrozenTaskActionExecutor(store).execute(com.shifthud.widget.FrozenTaskCommand(monday,rendered.id,rendered.revision,true)))
        val saved=tasks().single {it.id==optional.id}
        job.cancelAndJoin();job=SupervisorJob();store=create()
        assertEquals(saved,tasks().single {it.id==optional.id})
        assertTrue(dailyFrozenRows(tasks(),monday).single {it.task.id==optional.id}.task.state==FrozenState.COMPLETED)
        store.setDailyState(monday,optional.id,FrozenState.TODO)
        assertFalse(com.shifthud.widget.widgetFrozenState(tasks(),FloatCounts(),monday).tasks.single {it.id==optional.id}.completed)
        store.openDaily(monday.plusDays(1))
        assertFalse(dailyFrozenRows(tasks(),monday.plusDays(1)).any {it.sources.any {source->source.id==optional.id}})
        assertTrue(dailyFrozenRows(tasks(),monday.plusDays(1)).any {it.task.state==FrozenState.CARRY_OVER})
        assertEquals(1,dailyFrozenRows(tasks(),monday.plusDays(1)).count {it.task.id.startsWith("endcap-first-")})
    }
    @Test fun existingUnfinishedChecklistMergesOnceWithoutChangingOldFacts()=runBlocking {
        store.openDaily(monday)
        val old=tasks().take(10).mapIndexed {i,t->if(i==0)t.copy(state=FrozenState.COMPLETED,notes="Keep note",history=listOf(FrozenEvent(FrozenState.COMPLETED,Instant.EPOCH))) else t}
        dataStore!!.edit {it[androidx.datastore.preferences.core.stringPreferencesKey("tasks_v1")]=store.encode(old)}
        coroutineScope {repeat(8) {launch {store.openDaily(monday)}}}
        assertEquals(old,tasks().take(10));assertEquals(13,tasks().size)
        val allDone=tasks().map {it.copy(state=FrozenState.COMPLETED)}.take(10)
        dataStore!!.edit {it[androidx.datastore.preferences.core.stringPreferencesKey("tasks_v1")]=store.encode(allDone)}
        store.openDaily(monday);assertEquals(allDone,tasks())
    }
    @Test fun noTrainingOnUnscheduledDayAndHistoricalRecordsAreNotRegenerated()=runBlocking {
        scheduled=emptySet();store.openDaily(monday)
        assertFalse(tasks().any {it.optionalTraining})
        scheduled=setOf(monday);store.openDaily(monday)
        assertEquals(1,tasks().count {it.optionalTraining})
        val old=monday.minusWeeks(1);store.openDaily(old);val before=tasks().filter {it.date==old}
        store.openDaily(old);assertEquals(before,tasks().filter {it.date==old});assertEquals(10,before.size)
        assertTrue(dailyFrozenTemplate(monday.plusDays(3),true).isEmpty())
        assertTrue(dailyFrozenTemplate(monday.plusDays(5),true).isEmpty())
    }
    @Test fun historicalUnfinishedChecklistIsNotRetroactivelyExpanded()=runBlocking {
        store.openDaily(monday)
        val original=tasks().take(10)
        dataStore!!.edit {it[androidx.datastore.preferences.core.stringPreferencesKey("tasks_v1")]=store.encode(original)}
        job.cancelAndJoin();job=SupervisorJob();now=now.plusSeconds(86400);store=create()
        store.openDaily(monday)
        assertEquals(original,tasks())
        // Legacy records may predate template-registration markers.
        dataStore!!.edit {it.remove(androidx.datastore.preferences.core.stringSetPreferencesKey("daily_template_dates_v1"))}
        store.openDaily(monday)
        assertEquals(original,tasks())
    }
    @Test fun scheduledPreviewUsesSaturdayFridayWeekWithoutPersisting() {
        val shift=com.shifthud.domain.model.ScheduledShift(1,monday,LocalTime.of(8,0),LocalTime.of(17,0))
        val week=frozenPlanningWeek(emptyList(),listOf(shift),monday,monday.minusDays(1))
        assertEquals(monday.minusDays(2),week.first().date)
        assertEquals(monday.plusDays(4),week.last().date)
        assertEquals(13,week.single {it.date==monday}.rows.size)
        assertEquals(1,week.single {it.date==monday}.rows.count {it.task.optionalTraining})
        assertFalse(week.single {it.date==monday.plusDays(1)}.rows.any {it.task.optionalTraining})
    }
}
