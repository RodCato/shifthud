package com.shifthud.ui

import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import com.shifthud.data.local.entity.entity
import com.shifthud.notification.*
import com.shifthud.notification.lunch.*
import com.shifthud.service.ActiveShiftService
import com.shifthud.ui.settings.SettingsScreen
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.*

class LunchAlertsDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    @Test fun settingsExposeAccessibleLunchControls() {
        if(InstrumentationRegistry.getArguments().getString("phase")!=null)return
        val vm=ShiftViewModel(app)
        ui.setContent { ShiftHudTheme { SettingsScreen(ShiftUiState(loaded=true),vm,false) } }
        ui.onNodeWithText("Search settings").performTextReplacement("Lunch Alerts")
        ui.onNode(hasText("During lunch · minimum, planned and snooze") and hasClickAction()).performScrollTo().performClick()
        ui.onNodeWithText("Minimum lunch alert enabled").performScrollTo().assertHasClickAction()
        ui.onNodeWithText("Custom snooze minutes (1–120)").performScrollTo().assertExists()
        ui.onNodeWithText("Test lunch snooze alert").performScrollTo().assertHasClickAction()
    }
    /** Opt-in fixture only on preserved, empty emulator data. */
    @Test fun realAlertSnoozeAndRecovery()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("phase") ?: return@runBlocking
        if(!phase.startsWith("lunch_"))return@runBlocking
        val manager=app.getSystemService(NotificationManager::class.java)
        val fixture=app.getSharedPreferences("lunch_test_fixture",0)
        if(phase=="lunch_seed") {
            check(app.repository.sessions.first().isEmpty())
            app.lunchAlerts.save(LunchAlertSettings(planned=true,snoozeMinutes=1))
            val start=Instant.now().minusSeconds(31*60-15)
            val record=WorkSession(9003,clockIn=start.minusSeconds(4*3600),lunchStart=start,state=ShiftState.ON_LUNCH)
            app.preferences.setWeeklyLunch(com.shifthud.domain.pay.workWeekFor(record.clockIn.atZone(ZoneId.systemDefault()).toLocalDate()).start,48)
            app.database.shifts().insert(record.entity())
            fixture.edit().putLong("start",start.toEpochMilli()).commit()
            app.lunchAlerts.refresh();assertTrue(manager.activeNotifications.none{it.id==LunchAlerts.ID})
            ActiveShiftService.start(app)
            return@runBlocking
        }
        val record=app.repository.sessions.first().single { it.id==9003L }
        assertEquals(fixture.getLong("start",-1),record.lunchStart!!.toEpochMilli())
        if(phase!="lunch_end") { assertNull(record.lunchEnd);assertEquals(ShiftState.ON_LUNCH,record.state) }
        if(phase=="lunch_snooze") {
            withTimeout(25_000) { while(manager.activeNotifications.none{it.id==LunchAlerts.ID}){app.lunchAlerts.refresh();delay(500)} }
            val n=manager.activeNotifications.single{it.id==LunchAlerts.ID}.notification
            assertTrue(Instant.now()>=record.lunchStart!!.plusSeconds(31*60))
            assertEquals("ShiftHUD — Lunch Minimum Reached",n.extras.getString("android.title"))
            assertEquals(ShiftNotifications.WARNING_CHANNEL,n.channelId)
            n.actions.first().actionIntent.send()
            withTimeout(5_000){while(manager.activeNotifications.any{it.id==LunchAlerts.ID} || app.lunchAlerts.next.value?.let{it>Instant.now().plusSeconds(30)}!=true)delay(100)}
            fixture.edit().putLong("target",app.lunchAlerts.next.value!!.toEpochMilli()).commit()
            assertEquals(record,app.repository.sessions.first().single{it.id==9003L})
        } else if(phase=="lunch_recover") {
            app.lunchAlerts.refresh()
            val target=Instant.ofEpochMilli(fixture.getLong("target",-1))
            if(Instant.now()<target)assertEquals(target,app.lunchAlerts.next.value)
            withTimeout(75_000){while(manager.activeNotifications.none{it.id==LunchAlerts.ID}){app.lunchAlerts.refresh();delay(500)}}
            val n=manager.activeNotifications.single{it.id==LunchAlerts.ID}.notification
            assertEquals("ShiftHUD — Lunch Reminder",n.extras.getString("android.title"))
            assertTrue(Instant.now()>=target)
            manager.cancel(LunchAlerts.ID);app.lunchAlerts.refresh();assertTrue(manager.activeNotifications.none{it.id==LunchAlerts.ID})
            assertEquals(record,app.repository.sessions.first().single{it.id==9003L})
        } else if(phase=="lunch_end") {
            if(record.state==ShiftState.ON_LUNCH) app.repository.transition(record.id,ShiftState.ON_LUNCH,app.engine::endLunch)
            else assertEquals(ShiftState.WORKING,record.state)
            assertNull(app.lunchAlerts.next.value);assertTrue(manager.activeNotifications.none{it.id==LunchAlerts.ID})
            val after=app.repository.sessions.first().single{it.id==9003L}
            assertEquals(record.lunchStart,after.lunchStart);assertEquals(record.clockIn,after.clockIn);assertNotNull(after.lunchEnd)
            listOf(TestNotificationChannel.MINIMUM_LUNCH,TestNotificationChannel.PLANNED_LUNCH,TestNotificationChannel.LUNCH_SNOOZE).forEach {
                delay(3100) // Respect the shared channel/application alert rate.
                assertTrue(app.notifications.testCenter.post(it).posted)
                withTimeout(5000) { while(manager.activeNotifications.none { n->n.id==it.testId }) delay(100) }
                assertEquals(ShiftNotifications.WARNING_CHANNEL,manager.activeNotifications.single{n->n.id==it.testId}.notification.channelId)
                manager.cancel(it.testId)
            }
            assertEquals(after,app.repository.sessions.first().single{it.id==9003L})
        }
    }
}
