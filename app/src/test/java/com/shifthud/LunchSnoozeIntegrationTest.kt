package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import com.shifthud.widget.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LunchSnoozeIntegrationTest {
    private val start = Instant.parse("2026-10-05T08:00:00Z")
    private val engine = ShiftEngine(Clock.fixed(start.plusSeconds(301), ZoneOffset.UTC))
    private lateinit var db: ShiftDatabase
    private lateinit var repo: ShiftRepository
    private lateinit var prefs: ShiftPreferences
    private var latest = AttentionDecision(null)
    private var now = start.plusSeconds(602)
    private suspend fun refresh() {
        latest = prefs.deliverAttention(repo.snapshot().session, now, engine, true) { _, _ -> true }
    }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ShiftDatabase::class.java).build()
        prefs = ShiftPreferences(RuntimeEnvironment.getApplication()); prefs.setLunchThreshold(5)
        prefs.deliverAttention(null, now, engine, true) { _, _ -> false }
        repo = ShiftRepository(db, engine, onChanged = { refresh() })
        repo.clockIn()
        val s = repo.snapshot().session!!
        assertTrue(prefs.snooze(s.id, latest.state!!.receipt, { repo.snapshot().session }, now, engine))
        refresh(); assertNotNull(latest.state!!.targetActiveMillis)
    }
    @After fun close() = runBlocking {
        prefs.setLunchThreshold(360)
        prefs.deliverAttention(null, now, engine, true) { _, _ -> false }
        db.close()
    }
    @Test fun dashboardTransitionClearsSnoozeThroughSharedRefresh() = runBlocking {
        val s = repo.snapshot().session!!
        repo.transition(s.id, s.state, engine::startLunch)
        assertNull(latest.state)
    }
    @Test fun widgetStartLunchClearsSnoozeThroughSharedRefresh() = runBlocking {
        val s = repo.snapshot().session!!
        WidgetActionExecutor(repo, engine) { refresh() }.execute(WidgetCommand(WidgetOperation.START_LUNCH, s.id, LocalDate.of(2026,10,5)))
        assertNull(latest.state)
        assertEquals(ShiftState.ON_LUNCH, repo.snapshot().session!!.state)
    }
    @Test fun notificationStartAndCompletedLunchSuppressAllAttention() = runBlocking {
        val s = repo.snapshot().session!!
        val actions = NotificationActionExecutor(repo, engine) { refresh() }
        actions.execute(LunchAction.START_LUNCH, s.id); assertNull(latest.state)
        actions.execute(LunchAction.END_LUNCH, s.id)
        now = start.plusSeconds(9000); refresh(); assertNull(latest.state)
    }
    @Test fun clockOutClearsSnoozeThroughSharedRefresh() = runBlocking {
        val s = repo.snapshot().session!!
        repo.transition(s.id, s.state, engine::clockOut)
        assertNull(latest.state)
    }
    @Test fun attentionNotificationHasChannelOwnedSoundAndUniqueSnoozeToken() {
        val notifications = ShiftNotifications(RuntimeEnvironment.getApplication())
        val first = notifications.attentionReminder(1, Duration.ofMinutes(5), LunchAttention(1), 10)
        val next = notifications.attentionReminder(1, Duration.ofMinutes(15), LunchAttention(1, 1, 900000), 5)
        assertEquals(ShiftNotifications.WARNING_CHANNEL, first.channelId)
        assertEquals("Lunch time", first.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Lunch reminder", next.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(listOf("START LUNCH", "SNOOZE 10M", "OPEN"), first.actions.map { it.title.toString() })
        assertEquals("SNOOZE 5M", next.actions[1].title)
        assertNull(first.sound); assertNull(first.vibrate)
        assertEquals(android.app.Notification.CATEGORY_REMINDER, first.category)
        assertTrue(first.actions[1].actionIntent.isImmutable)
        assertNotEquals(first.actions[1].actionIntent, next.actions[1].actionIntent)
        val intent = Shadows.shadowOf(first.actions[1].actionIntent).savedIntent
        assertEquals(1L, intent.getLongExtra("sessionId", 0))
        assertEquals("1:attention:0", intent.getStringExtra("receipt"))
    }
}
