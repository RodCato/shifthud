package com.shifthud

import android.content.Context
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShiftRepositoryTest {
    private lateinit var db: ShiftDatabase
    private lateinit var repository: ShiftRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-03T09:00:00Z"), ZoneOffset.UTC)
    private val engine = ShiftEngine(clock)
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun open() {
        db = Room.databaseBuilder(context, ShiftDatabase::class.java, "test.db").build()
        repository = ShiftRepository(db, engine, clock)
    }
    @Before fun setup() { context.deleteDatabase("test.db"); open() }
    @After fun close() { db.close(); context.deleteDatabase("test.db") }
    private fun shift(id: Long = 0, time: String = "04:00", date: String = "2026-10-03") = ScheduledShift(id, LocalDate.parse(date), LocalTime.parse(time), LocalTime.of(13, 0))

    @Test fun clockInAutomaticallyLinksEarliestToday() = runBlocking {
        repository.save(shift(time = "08:00")); repository.save(shift(time = "04:00"))
        repository.save(shift(date = "2026-10-04"))
        repository.clockIn()
        val schedule = repository.schedule.first()
        assertEquals(LocalTime.of(4, 0), schedule.first().scheduledStart)
        assertEquals(schedule.first().id, repository.latestSession.first()!!.scheduledShiftId)
    }
    @Test fun unscheduledClockInHasNoAssociation() = runBlocking {
        repository.clockIn()
        assertNull(repository.latestSession.first()!!.scheduledShiftId)
    }
    @Test fun concurrentClockInsProduceOnlyOneActiveSession() = runBlocking {
        val successes = coroutineScope { (1..2).map { async(Dispatchers.IO) { runCatching { repository.clockIn() }.isSuccess } }.awaitAll() }
        assertEquals(1, successes.count { it })
    }
    @Test fun editDoesNotDeleteLinkedSessionAndDeleteRetainsTimes() = runBlocking {
        repository.save(shift()); repository.clockIn()
        val original = repository.latestSession.first()!!
        val schedule = repository.schedule.first().single()
        repository.save(schedule.copy(notes = "Updated", scheduledEnd = LocalTime.of(14, 0)))
        assertEquals(original, repository.latestSession.first())
        assertEquals("Updated", repository.schedule.first().single().notes)
        repository.delete(schedule.id)
        assertEquals(original.copy(scheduledShiftId = null), repository.latestSession.first())
        assertTrue(repository.schedule.first().isEmpty())
    }
    @Test fun databaseReopenRestoresActiveLunch() = runBlocking {
        repository.clockIn()
        val s = repository.latestSession.first()!!
        repository.transition(s.id, s.state, engine::startLunch)
        db.close(); open()
        val restored = repository.latestSession.first()!!
        assertEquals(ShiftState.ON_LUNCH, restored.state)
        assertEquals(clock.instant(), restored.lunchStart)
        assertEquals(Duration.ofMinutes(20), engine.durations(restored, 360, clock.instant().plusSeconds(1200)).lunch)
    }
    @Test fun staleTransitionCannotOverwriteNewState() = runBlocking {
        repository.clockIn()
        val s = repository.latestSession.first()!!
        repository.transition(s.id, s.state, engine::startLunch)
        assertTrue(runCatching { repository.transition(s.id, s.state, engine::clockOut) }.isFailure)
        assertEquals(ShiftState.ON_LUNCH, repository.latestSession.first()!!.state)
    }
}
