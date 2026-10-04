package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.ShiftState
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationActionTest {
    private lateinit var db: ShiftDatabase
    private lateinit var repo: ShiftRepository
    private lateinit var actions: NotificationActionExecutor
    private val engine = ShiftEngine(Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC))
    private var refreshes = 0
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ShiftDatabase::class.java).build()
        repo = ShiftRepository(db, engine)
        actions = NotificationActionExecutor(repo, engine) { refreshes++ }
    }
    @After fun close() { db.close() }
    @Test fun startLunchUsesExistingRepositoryTransition() = runBlocking {
        repo.clockIn()
        assertTrue(actions.execute(LunchAction.START_LUNCH, repo.snapshot().session!!.id))
        assertEquals(ShiftState.ON_LUNCH, repo.snapshot().session!!.state)
        assertEquals(1, refreshes)
    }
    @Test fun endLunchUsesExistingRepositoryTransition() = runBlocking {
        repo.clockIn(); val id = repo.snapshot().session!!.id
        actions.execute(LunchAction.START_LUNCH, id)
        assertTrue(actions.execute(LunchAction.END_LUNCH, id))
        assertEquals(ShiftState.WORKING, repo.snapshot().session!!.state)
        assertNotNull(repo.snapshot().session!!.lunchEnd)
    }
    @Test fun repeatedActionsAndSecondLunchNoOpAndReconcile() = runBlocking {
        repo.clockIn(); val id = repo.snapshot().session!!.id
        actions.execute(LunchAction.START_LUNCH, id)
        val lunch = repo.snapshot()
        assertFalse(actions.execute(LunchAction.START_LUNCH, id)); assertEquals(lunch, repo.snapshot())
        actions.execute(LunchAction.END_LUNCH, id)
        val after = repo.snapshot()
        assertFalse(actions.execute(LunchAction.END_LUNCH, id))
        assertFalse(actions.execute(LunchAction.START_LUNCH, id))
        assertEquals(after, repo.snapshot()); assertEquals(5, refreshes)
    }
    @Test fun oldNotificationCannotMutateNewSession() = runBlocking {
        repo.clockIn(); val old = repo.snapshot().session!!
        repo.transition(old.id, old.state, engine::clockOut)
        repo.clockIn(); val before = repo.snapshot()
        assertFalse(actions.execute(LunchAction.START_LUNCH, old.id))
        assertEquals(before, repo.snapshot())
    }
    @Test fun completedSessionRejectsOldAction() = runBlocking {
        repo.clockIn(); val old = repo.snapshot().session!!
        repo.transition(old.id, old.state, engine::clockOut)
        assertFalse(actions.execute(LunchAction.START_LUNCH, old.id))
        assertEquals(ShiftState.COMPLETE, repo.snapshot().session!!.state)
    }
}
