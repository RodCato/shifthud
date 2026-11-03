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
class FloatCounterTest {
    private val file=Files.createTempDirectory("float-counter").resolve("frozen.preferences_pb").toFile()
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
    private suspend fun counts()=store.floats.first()
    @Test fun incrementDecrementAndZeroFloorNeedNoShift()=runBlocking {
        assertEquals(0L,counts()[today]);store.adjustFloat(-1);assertEquals(0L,counts()[today])
        repeat(3){store.adjustFloat(1)};store.adjustFloat(-1);assertEquals(2L,counts()[today])
        assertTrue(store.tasks.first().isEmpty());assertEquals("",store.selectedWorkflow.first())
    }
    @Test fun rapidDistinctAppAndWidgetTapsAreNotLost()=runBlocking {
        val actions=FloatActionExecutor(store)
        coroutineScope {(1..100).map {n->async(Dispatchers.IO) {
            if(n%2==0) store.adjustFloat(1,"app-$n") else assertTrue(actions.execute(FloatCommand(1,"widget-$n")))
        }}.awaitAll()}
        assertEquals(100L,counts()[today]);assertEquals(100,refreshes.get())
        coroutineScope {repeat(120) {launch(Dispatchers.IO) {store.adjustFloat(-1)}}}
        assertEquals(0L,counts()[today])
    }
    @Test fun sameCommandRetryIsDeduplicatedIncludingAfterRestart()=runBlocking {
        val command=FloatCommand(1,"stable-retry-id")
        val actions=FloatActionExecutor(store)
        coroutineScope {repeat(10) {launch {assertTrue(actions.execute(command))}}}
        assertEquals(1L,counts()[today]);assertEquals(1,refreshes.get())
        job.cancelAndJoin();job=SupervisorJob();store=create()
        assertTrue(FloatActionExecutor(store).execute(command));assertEquals(1L,counts()[today])
        assertTrue(FloatActionExecutor(store).execute(FloatCommand(1)));assertEquals(2L,counts()[today])
    }
    @Test fun rolloverKeepsYesterdayAndStaleWidgetActionUsesCurrentLocalDate()=runBlocking {
        repeat(5) {store.adjustFloat(1)}
        now=Instant.parse("2026-11-03T06:01:00Z")
        assertEquals(0L,counts()[today.plusDays(1)])
        assertTrue(FloatActionExecutor(store).execute(FloatCommand(1)))
        assertEquals(5L,counts()[today]);assertEquals(1L,counts()[today.plusDays(1)])
        job.cancelAndJoin();job=SupervisorJob();store=create()
        assertEquals(5L,counts()[today]);assertEquals(1L,counts()[today.plusDays(1)])
    }
    @Test fun timezoneAndYearChangesUseLocalDateWithoutResettingHistory()=runBlocking {
        now=Instant.parse("2027-01-01T01:00:00Z")
        store.adjustFloat(1);assertEquals(1L,counts()[LocalDate.of(2026,12,31)])
        zone=ZoneId.of("Asia/Tokyo");store.adjustFloat(1)
        assertEquals(1L,counts()[LocalDate.of(2027,1,1)]);assertEquals(1L,counts()[LocalDate.of(2026,12,31)])
    }
    @Test fun widgetAndTasksReadTheSameValueWithoutChangingTaskFacts()=runBlocking {
        store.openDaily(today);val before=store.tasks.first()
        val actions=FloatActionExecutor(store)
        repeat(3){assertTrue(actions.execute(FloatCommand(1)))}
        assertEquals(3L,counts()[today]);store.adjustFloat(1)
        assertEquals(4L,widgetFrozenState(store.tasks.first(),counts(),today).floatCount)
        assertEquals(before,store.tasks.first())
    }
    @Test fun countsAggregateSaturdayFridayWeekAndCalendarMonth() {
        val counts=FloatCounts(mapOf(LocalDate.of(2026,10,31) to 2L,LocalDate.of(2026,11,1) to 3L,today to 4L,LocalDate.of(2026,11,7) to 7L))
        assertEquals(9L,counts.week(today));assertEquals(14L,counts.month(today));assertEquals(4L,counts[today])
    }
    @Test fun widgetInitializationIsIdempotentAndDoesNotChangeBrowsedWorkflow()=runBlocking {
        store.openDaily(today.minusDays(1));val selected=store.selectedWorkflow.first()
        store.openDaily(today,select=false,notify=false);val before=store.tasks.first();val refresh=refreshes.get()
        repeat(5){store.openDaily(today,select=false,notify=false)}
        assertEquals(selected,store.selectedWorkflow.first());assertEquals(before,store.tasks.first());assertEquals(refresh,refreshes.get())
        store.saveDetails(before.last().id,before.last().title,before.last().description,"New note")
        assertEquals(refresh+1,refreshes.get())
    }
    @Test fun invalidWidgetCommandCannotChangeStoredCount()=runBlocking {
        assertFalse(FloatActionExecutor(store).execute(FloatCommand(2)))
        assertEquals(0L,counts()[today]);assertEquals(0,refreshes.get())
    }
}
