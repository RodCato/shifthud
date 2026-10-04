package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.widget.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetActionTest {
    private lateinit var db: ShiftDatabase
    private lateinit var repo: ShiftRepository
    private lateinit var actions: WidgetActionExecutor
    private val day = LocalDate.of(2026, 10, 5)
    private val clock = Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), ZoneOffset.UTC)
    private val engine = ShiftEngine(clock)
    private var writeRefreshes = 0
    private var actionRefreshes = 0
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ShiftDatabase::class.java).build()
        repo = ShiftRepository(db, engine, clock, onChanged = { writeRefreshes++ })
        actions = WidgetActionExecutor(repo, engine) { actionRefreshes++ }
    }
    @After fun close() { db.close() }
    private fun command(op: WidgetOperation, id: Long = 0, date: LocalDate = day) = WidgetCommand(op, id, date)

    @Test fun widgetLifecycleUsesRepositoryAndAutomaticallyAssociatesToday() = runBlocking {
        repo.save(ScheduledShift(date = day, scheduledStart = LocalTime.of(4, 0), scheduledEnd = LocalTime.of(13, 0)))
        assertTrue(actions.execute(command(WidgetOperation.CLOCK_IN)))
        val session = repo.snapshot().session!!
        assertEquals(repo.snapshot().schedule.single().id, session.scheduledShiftId)
        assertTrue(actions.execute(command(WidgetOperation.START_LUNCH, session.id)))
        assertEquals(ShiftState.ON_LUNCH, repo.snapshot().session!!.state)
        assertTrue(actions.execute(command(WidgetOperation.END_LUNCH, session.id)))
        assertEquals(ShiftState.WORKING, repo.snapshot().session!!.state)
        assertEquals(3, actionRefreshes)
        assertEquals(4, writeRefreshes)
    }
    @Test fun duplicateClockInAcrossInstancesFailsSafely() = runBlocking {
        val results = coroutineScope { (1..2).map { async(Dispatchers.IO) { actions.execute(command(WidgetOperation.CLOCK_IN)) } }.awaitAll() }
        assertEquals(1, results.count { it })
        assertEquals(ShiftState.WORKING, repo.snapshot().session!!.state)
    }
    @Test fun repeatedLunchAndEndActionsRefreshButDoNotAlterData() = runBlocking {
        actions.execute(command(WidgetOperation.CLOCK_IN))
        val id = repo.snapshot().session!!.id
        val lunch = command(WidgetOperation.START_LUNCH, id)
        val end = command(WidgetOperation.END_LUNCH, id)
        assertTrue(actions.execute(lunch))
        val before = repo.snapshot().session
        assertFalse(actions.execute(lunch))
        assertEquals(before, repo.snapshot().session)
        assertTrue(actions.execute(end))
        assertFalse(actions.execute(end))
        assertFalse(actions.execute(lunch)) // Existing engine forbids a second lunch.
        assertEquals(6, actionRefreshes)
    }
    @Test fun oldWidgetCannotChangeNewSessionOrRestartCompletedOne() = runBlocking {
        val oldClockIn = command(WidgetOperation.CLOCK_IN)
        actions.execute(oldClockIn)
        val old = repo.snapshot().session!!
        repo.transition(old.id, old.state, engine::clockOut)
        assertFalse(actions.execute(oldClockIn))
        repo.clockIn()
        assertFalse(actions.execute(command(WidgetOperation.START_LUNCH, old.id)))
        assertEquals(ShiftState.WORKING, repo.snapshot().session!!.state)
    }
    @Test fun staleDateRejectsClockInAndRefreshes() = runBlocking {
        assertFalse(actions.execute(command(WidgetOperation.CLOCK_IN, date = day.minusDays(1))))
        assertNull(repo.snapshot().session)
        assertEquals(1, actionRefreshes)
    }
    @Test fun appWritesRequestRefreshIncludingClockOutAndScheduleCrud() = runBlocking {
        repo.save(ScheduledShift(date = day, scheduledStart = LocalTime.of(4, 0), scheduledEnd = LocalTime.of(13, 0)))
        val shift = repo.snapshot().schedule.single()
        repo.save(shift.copy(notes = "Edit"))
        repo.clockIn()
        val s = repo.snapshot().session!!
        repo.transition(s.id, s.state, engine::clockOut)
        repo.delete(shift.id)
        assertEquals(5, writeRefreshes)
    }
    @Test fun refreshActionDoesNotCreateASession() = runBlocking {
        assertTrue(actions.execute(command(WidgetOperation.REFRESH)))
        assertNull(repo.snapshot().session)
        assertEquals(1, actionRefreshes)
        assertEquals(0, writeRefreshes)
    }
    @Test fun refreshOfActiveSessionDoesNotModifyPersistedEvents() = runBlocking {
        actions.execute(command(WidgetOperation.CLOCK_IN))
        val before = repo.snapshot()
        val writeCount = writeRefreshes
        repeat(5) { assertTrue(actions.execute(command(WidgetOperation.REFRESH))) }
        assertEquals(before, repo.snapshot())
        assertEquals(writeCount, writeRefreshes)
    }
    @Test fun missingSessionActionFailsAndRefreshes() = runBlocking {
        assertFalse(actions.execute(command(WidgetOperation.END_LUNCH, 999)))
        assertNull(repo.snapshot().session)
        assertEquals(1, actionRefreshes)
    }
}
