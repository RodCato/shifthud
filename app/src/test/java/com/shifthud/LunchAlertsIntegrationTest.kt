package com.shifthud

import android.Manifest
import android.app.NotificationManager
import com.shifthud.notification.lunch.*
import com.shifthud.notification.ShiftNotifications
import com.shifthud.domain.model.*
import com.shifthud.data.local.entity.entity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ShiftHudApplication::class)
class LunchAlertsIntegrationTest {
    private val app get()=RuntimeEnvironment.getApplication() as ShiftHudApplication
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private val start=LocalDate.of(2026,10,17).atTime(0,5).atZone(ZoneId.systemDefault()).toInstant()
    private var now=start
    private val session=WorkSession(700,clockIn=start.minusSeconds(5*3600),lunchStart=start,state=ShiftState.ON_LUNCH)
    @Before fun seed()=runBlocking {
        app.getSharedPreferences("on_lunch_alerts",0).edit().clear().commit()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.database.shifts().insert(session.entity())
        Unit
    }
    @Test fun restartDeduplicatesAndKeepsPunchesAndExistingPreferences()=runBlocking {
        val before=app.preferences.store.data.first()
        val alerts=LunchAlerts(app){now};alerts.refresh()
        now=start.plusSeconds(31*60);alerts.refresh()
        val n=manager.activeNotifications.single{it.id==LunchAlerts.ID}
        assertEquals(ShiftNotifications.WARNING_CHANNEL,n.notification.channelId)
        assertEquals("ShiftHUD — Lunch Minimum Reached",n.notification.extras.getString("android.title"))
        assertEquals(listOf("SNOOZE 10M","OPEN SHIFTHUD"),n.notification.actions.map{it.title.toString()})
        manager.cancel(LunchAlerts.ID)
        LunchAlerts(app){now}.refresh();assertTrue(manager.activeNotifications.none{it.id==LunchAlerts.ID})
        assertEquals(session,app.repository.sessions.first().single());assertEquals(before,app.preferences.store.data.first())
    }
    @Test fun permissionDenialRemainsRetryableWithoutEarlyAlert()=runBlocking {
        val alerts=LunchAlerts(app){now};alerts.refresh()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        now=start.plusSeconds(31*60);alerts.refresh();assertTrue(manager.activeNotifications.none{it.id==LunchAlerts.ID})
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        alerts.refresh();assertTrue(manager.activeNotifications.any{it.id==LunchAlerts.ID})
    }
    @Test fun snoozePersistsExactlyOneTargetAndLunchEndCancels()=runBlocking {
        val alerts=LunchAlerts(app){now};alerts.refresh();now=start.plusSeconds(31*60);alerts.refresh()
        val receipt=manager.activeNotifications.single{it.id==LunchAlerts.ID}.notification.extras.getString(LunchAlerts.RECEIPT)!!
        now=now.plusSeconds(45);assertTrue(alerts.snooze(receipt));assertFalse(alerts.snooze(receipt))
        val recovered=LunchAlerts(app){now};recovered.refresh();assertEquals(now.plusSeconds(600),recovered.next.value)
        assertEquals(session,app.repository.sessions.first().single())
        app.database.shifts().update(session.copy(lunchEnd=now,state=ShiftState.WORKING).entity())
        recovered.refresh();assertNull(recovered.next.value);assertTrue(manager.activeNotifications.none{it.id==LunchAlerts.ID})
    }
    @Test fun settingsPersistAndChannelSettingsStayUnchanged()=runBlocking {
        app.notifications.createChannels()
        val channel=manager.getNotificationChannel(ShiftNotifications.WARNING_CHANNEL)
        val sound=channel.sound;val vibration=channel.shouldVibrate()
        val alerts=LunchAlerts(app){now};alerts.save(LunchAlertSettings(false,true,17,true))
        val recovered=LunchAlerts(app){now};assertEquals(LunchAlertSettings(false,true,17,true),recovered.settings.value)
        assertEquals(sound,manager.getNotificationChannel(ShiftNotifications.WARNING_CHANNEL).sound)
        assertEquals(vibration,manager.getNotificationChannel(ShiftNotifications.WARNING_CHANNEL).shouldVibrate())
        assertEquals(session,app.repository.sessions.first().single())
    }
    @Test fun enablingPendingAlertDoesNotReplayPastBoundary()=runBlocking {
        val alerts=LunchAlerts(app){now};alerts.save(LunchAlertSettings(minimum=false));now=start.plusSeconds(32*60)
        alerts.save(LunchAlertSettings(minimum=true));assertTrue(manager.activeNotifications.none{it.id==LunchAlerts.ID})
    }
    @Test fun overnightPlanUsesShiftStartWeekInsteadOfLunchDay()=runBlocking {
        val workWeek=com.shifthud.domain.pay.workWeekFor(session.clockIn.atZone(ZoneId.systemDefault()).toLocalDate()).start
        app.preferences.setWeeklyLunch(workWeek,48)
        app.preferences.setWeeklyLunch(workWeek.plusWeeks(1),75)
        val alerts=LunchAlerts(app){now};alerts.save(LunchAlertSettings(planned=true))
        now=start.plusSeconds(31*60);alerts.refresh()
        assertEquals(start.plusSeconds(48*60),alerts.next.value)
        assertEquals(session,app.repository.sessions.first().single())
    }

}
