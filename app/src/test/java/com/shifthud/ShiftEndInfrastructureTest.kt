package com.shifthud

import android.app.*
import android.net.Uri
import android.provider.Settings
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.data.repository.ShiftSnapshot
import com.shifthud.domain.model.*
import com.shifthud.notification.*
import com.shifthud.service.ActiveShiftService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ShiftEndInfrastructureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneOffset.UTC
    private val end = Instant.parse("2026-10-07T13:00:00Z")
    private val target = end.minusSeconds(900)
    private val session = WorkSession(7,8,end.minusSeconds(9*3600))
    private val snapshot = ShiftSnapshot(listOf(ScheduledShift(8,LocalDate.of(2026,10,7),LocalTime.of(4,0),LocalTime.of(13,0))),session)
    @org.junit.Before fun resetPreferences(): Unit = runBlocking {
        // The delegate's DataStore survives Robolectric method sandboxes; isolate each fixture.
        ShiftPreferences(context).apply { setShiftEnd(ShiftEndSettings()); forgetSession(7) }
    }
    @Test fun defaultsAndSettingsSurviveRecreation()=runBlocking {
        val p=ShiftPreferences(context);assertEquals(ShiftEndSettings(),p.shiftEndSettings.first())
        for (lead in SHIFT_END_LEADS) { p.setShiftEnd(ShiftEndSettings(true,lead,5));assertEquals(lead,ShiftPreferences(context).shiftEndSettings.first().leadMinutes) }
        p.setShiftEnd(ShiftEndSettings(false,20,15));assertEquals(ShiftEndSettings(false,20,15),ShiftPreferences(context).shiftEndSettings.first())
    }
    @Test fun rejectsInvalidSettings()=runBlocking { val p=ShiftPreferences(context);assertTrue(runCatching{p.setShiftEnd(ShiftEndSettings(leadMinutes=7))}.isFailure);assertTrue(runCatching{p.setShiftEnd(ShiftEndSettings(snoozeMinutes=20))}.isFailure) }
    @Test fun postThenAcknowledgeOnlyOnSuccess()=runBlocking {
        val p=ShiftPreferences(context)
        assertFalse(p.deliverShiftEnd(snapshot,target,zone){_,_->false}.state!!.posted)
        var posts=0
        val d=ShiftPreferences(context).deliverShiftEnd(snapshot,target.plusSeconds(3),zone){_,minutes->assertEquals(10,minutes);posts++;true}
        assertTrue(d.state!!.posted)
        repeat(5){p.deliverShiftEnd(snapshot,target.plusSeconds(60),zone){_,_->posts++;true}}
        assertEquals(1,posts)
    }
    @Test fun thrownPostRemainsRetryable()=runBlocking {
        val p=ShiftPreferences(context)
        assertTrue(runCatching{p.deliverShiftEnd(snapshot,target,zone){_,_->error("failed")}}.isFailure)
        assertTrue(p.deliverShiftEnd(snapshot,target,zone){_,_->true}.state!!.posted)
    }
    @Test fun persistedSnoozeRecoveryAndExactReceipt()=runBlocking {
        val p=ShiftPreferences(context);val state=p.deliverShiftEnd(snapshot,target,zone){_,_->true}.state!!
        assertTrue(p.snoozeShiftEnd(state.receipt,{snapshot},target,zone))
        assertFalse(p.snoozeShiftEnd(state.receipt,{snapshot},target,zone))
        val recovered=ShiftPreferences(context)
        assertEquals(target.plusSeconds(600),recovered.nextShiftEndTarget())
        assertFalse(recovered.deliverShiftEnd(snapshot,target.plusSeconds(599),zone){_,_->error("early")}.due)
        assertTrue(recovered.deliverShiftEnd(snapshot,target.plusSeconds(603),zone){_,_->true}.state!!.posted)
    }
    @Test fun completedClearsPersistedSnooze()=runBlocking {
        val p=ShiftPreferences(context);val state=p.deliverShiftEnd(snapshot,target,zone){_,_->true}.state!!
        p.snoozeShiftEnd(state.receipt,{snapshot},target,zone)
        val d=p.deliverShiftEnd(snapshot.copy(session=session.copy(clockOut=end,state=ShiftState.COMPLETE)),end,zone){_,_->error("complete")}
        assertNull(d.state);assertNull(p.nextShiftEndTarget());assertTrue(d.cancel)
    }
    @Test fun scheduleRemovalClearsPersistedTarget()=runBlocking {
        val p=ShiftPreferences(context);p.deliverShiftEnd(snapshot,target.minusSeconds(1),zone){_,_->error("early")}
        assertNull(p.deliverShiftEnd(snapshot.copy(schedule=emptyList()),target,zone){_,_->error("removed")}.state)
        assertNull(p.nextShiftEndTarget())
    }
    @Test fun deletionCleanupIsScoped()=runBlocking {
        val p=ShiftPreferences(context);p.deliverShiftEnd(snapshot,target.minusSeconds(1),zone){_,_->false}
        p.forgetSession(70);assertEquals(target,p.nextShiftEndTarget());p.forgetSession(7);assertNull(p.nextShiftEndTarget())
    }
    @Test fun separateHighChannelAndSilentOngoing(){
        val n=ShiftNotifications(context);n.createChannels();val manager=context.getSystemService(NotificationManager::class.java)
        val c=manager.getNotificationChannel(ShiftNotifications.END_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,c.importance);assertNotNull(c.sound);assertTrue(c.shouldVibrate());assertFalse(c.canBypassDnd())
        assertNotEquals(ShiftNotifications.WARNING_CHANNEL,c.id)
        val ongoing=manager.getNotificationChannel(ActiveShiftService.CHANNEL_ID);assertEquals(NotificationManager.IMPORTANCE_LOW,ongoing.importance);assertNull(ongoing.sound)
    }
    @Test fun userChannelConfigurationPreserved(){
        val manager=context.getSystemService(NotificationManager::class.java);val sound=Uri.parse("content://media/external/audio/media/42")
        manager.createNotificationChannel(NotificationChannel(ShiftNotifications.END_CHANNEL,"Shift end reminders",NotificationManager.IMPORTANCE_DEFAULT).apply{setSound(sound,null);enableVibration(false)})
        repeat(3){ShiftNotifications(context).createChannels()}
        val c=manager.getNotificationChannel(ShiftNotifications.END_CHANNEL);assertEquals(sound,c.sound);assertEquals(NotificationManager.IMPORTANCE_DEFAULT,c.importance);assertFalse(c.shouldVibrate())
    }
    @Test fun channelShortcutAndStatus(){
        val n=ShiftNotifications(context);n.createChannels();val i=n.shiftEndSettingsIntent()
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,i.action);assertEquals(ShiftNotifications.END_CHANNEL,i.getStringExtra(Settings.EXTRA_CHANNEL_ID));assertEquals("High",n.shiftEndStatus().importanceLabel)
    }
    @Test fun notificationContentActionsAndChannel(){
        val state=ShiftEndDelivery(7,end,15,target)
        val n=ShiftNotifications(context).shiftEndReminder(state,10,target)
        assertEquals(ShiftNotifications.END_CHANNEL,n.channelId);assertEquals(Notification.CATEGORY_REMINDER,n.category);assertNull(n.sound);assertNull(n.vibrate)
        assertEquals("Shift ends in 15 minutes",n.extras.getString(Notification.EXTRA_TITLE));assertTrue(n.extras.getString(Notification.EXTRA_TEXT)!!.contains("Time to wrap up"))
        assertEquals(listOf("SNOOZE 10M","OPEN"),n.actions.map{it.title.toString()})
        val intent=Shadows.shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(ShiftNotifications.END_SNOOZE_ACTION,intent.action);assertEquals(state.receipt,intent.getStringExtra("receipt"));assertTrue(n.actions[0].actionIntent.isImmutable)
    }
    @Test fun overdueNotificationWording(){
        val n=ShiftNotifications(context).shiftEndReminder(ShiftEndDelivery(7,end,15,end,generation=1),5,end)
        assertEquals("Scheduled shift ended",n.extras.getString(Notification.EXTRA_TITLE));assertTrue(n.extras.getString(Notification.EXTRA_TEXT)!!.contains("still clocked in"))
    }
}
