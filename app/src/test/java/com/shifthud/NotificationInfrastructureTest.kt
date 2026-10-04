package com.shifthud

import android.app.Application
import android.app.NotificationManager
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import com.shifthud.service.ActiveShiftService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationInfrastructureTest {
    @Test fun deliveryHistorySurvivesNewPreferencesWrapperAndDoesNotTriggerRefresh() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var refreshes = 0
        val prefs = ShiftPreferences(context) { refreshes++ }
        assertEquals(DEFAULT_WARNING_OFFSETS, prefs.warningSettings.first().offsets)
        val at = Instant.parse("2026-10-05T08:00:00Z")
        val engine = ShiftEngine(Clock.fixed(at, ZoneOffset.UTC))
        val session = WorkSession(id = 7, clockIn = at)
        prefs.deliverWarning(session, at, engine, true) { true }
        assertEquals(30, prefs.deliverWarning(session, at.plusSeconds(330 * 60), engine, true) { true }.offset)
        assertNull(ShiftPreferences(context).deliverWarning(session, at.plusSeconds(331 * 60), engine, true) { true }.offset)
        assertEquals(0, refreshes)
        prefs.setWarningEnabled(15, false)
        assertEquals(setOf(60, 30), prefs.warningSettings.first().offsets)
        assertNull(prefs.deliverWarning(session, at.plusSeconds(345 * 60), engine, true) { true }.offset)
        assertEquals(1, refreshes)
        assertEquals(at, session.clockIn)
        assertNull(session.lunchStart)
    }
    @Test fun channelsAreSeparateAndOngoingActionsAreImmutableAndSessionSpecific() {
        val context = RuntimeEnvironment.getApplication()
        val notifications = ShiftNotifications(context)
        notifications.createChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(ActiveShiftService.CHANNEL_ID).importance)
        assertNull(manager.getNotificationChannel(ActiveShiftService.CHANNEL_ID).sound)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(ShiftNotifications.WARNING_CHANNEL).importance)
        val state = ShiftNotificationState("ShiftHUD · Working", "Worked 5h 0m", emptyList(), LunchAction.START_LUNCH)
        val first = notifications.ongoing(state, 1)
        val second = notifications.ongoing(state, 2)
        assertEquals("START LUNCH", first.actions[0].title)
        assertEquals("OPEN", first.actions[1].title)
        assertTrue(first.actions[0].actionIntent.isImmutable)
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent)
        assertEquals(1L, Shadows.shadowOf(first.actions[0].actionIntent).savedIntent.getLongExtra("sessionId", 0))
    }
}
