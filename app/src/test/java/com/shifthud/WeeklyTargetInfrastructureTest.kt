package com.shifthud

import android.app.*
import android.net.Uri
import android.provider.Settings
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.domain.model.*
import com.shifthud.domain.weekly.*
import com.shifthud.notification.*
import com.shifthud.service.ActiveShiftService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class WeeklyTargetInfrastructureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val zone=ZoneOffset.UTC
    // Give each fixture its own workweek; DataStore delegates can outlive Robolectric sandboxes.
    private suspend fun prepare(day: String): Pair<ShiftPreferences, WorkSession> {
        val p=ShiftPreferences(context);p.setWeeklyTarget(WeeklyTargetSettings())
        val s=WorkSession(id=99,clockIn=Instant.parse("${day}T00:00:00Z"))
        p.deliverWeeklyWarning(listOf(s),emptyList(),s.clockIn,zone){_,_->error("initial")}
        return p to s
    }
    @Test fun settingsPersist()=runBlocking {
        val p=ShiftPreferences(context);p.setWeeklyTarget(WeeklyTargetSettings(false,2250,setOf(30)))
        assertEquals(WeeklyTargetSettings(false,2250,setOf(30)),ShiftPreferences(context).weeklyTargetSettings.first())
    }
    @Test fun postingAcknowledgementRetriesAndRecovers()=runBlocking {
        val (p,s)=prepare("2026-10-03");val due=s.clockIn.plusSeconds(38*3600)
        val failed=p.deliverWeeklyWarning(listOf(s),emptyList(),due,zone){_,_->false};assertFalse(120 in failed.ledger.consumed)
        var posts=0
        val sent=ShiftPreferences(context).deliverWeeklyWarning(listOf(s),emptyList(),due.plusSeconds(2),zone){_,_->posts++;true};assertTrue(120 in sent.ledger.consumed)
        repeat(5){p.deliverWeeklyWarning(listOf(s),emptyList(),due.plusSeconds(30),zone){_,_->posts++;true}}
        assertEquals(1,posts)
    }
    @Test fun restoredTargetKeepsPriorReceiptsAfterBackwardCorrection()=runBlocking {
        val (p,s)=prepare("2026-10-10");val due=s.clockIn.plusSeconds(38*3600)
        p.deliverWeeklyWarning(listOf(s),emptyList(),due,zone){_,_->true}
        p.setWeeklyTarget(WeeklyTargetSettings(targetMinutes=3000));p.deliverWeeklyWarning(listOf(s),emptyList(),due,zone){_,_->error("changed target")}
        val corrected=s.copy(clockIn=s.clockIn.plusSeconds(3600),correctionRevision=1)
        p.setWeeklyTarget(WeeklyTargetSettings());p.deliverWeeklyWarning(listOf(corrected),emptyList(),due,zone){_,_->error("restored target")}
        val next=p.deliverWeeklyWarning(listOf(corrected),emptyList(),due.plusSeconds(3600),zone){_,_->error("replayed")}
        assertNull(next.boundary)
    }
    @Test fun completedFixtureNeverPosts()=runBlocking {
        val (p,s)=prepare("2026-10-17");val end=s.clockIn.plusSeconds(2353*60)
        val complete=s.copy(clockOut=end,state=ShiftState.COMPLETE)
        val d=p.deliverWeeklyWarning(listOf(complete),emptyList(),end,zone){_,_->error("completed record alert")}
        assertTrue(d.cancel);assertNull(d.boundary)
    }
    @Test fun exceptionBeforeAcknowledgementRemainsRetryable()=runBlocking {
        val(p,s)=prepare("2026-10-24");val due=s.clockIn.plusSeconds(38*3600)
        assertTrue(runCatching{p.deliverWeeklyWarning(listOf(s),emptyList(),due,zone){_,_->error("posting failure")}}.isFailure)
        assertEquals(120,p.deliverWeeklyWarning(listOf(s),emptyList(),due,zone){_,_->true}.boundary)
    }
    @Test fun weeklyChannelDefaultsAndSilentActiveChannel(){
        val n=ShiftNotifications(context);n.createChannels();val manager=context.getSystemService(NotificationManager::class.java)
        val c=manager.getNotificationChannel(ShiftNotifications.WEEKLY_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,c.importance);assertNotNull(c.sound);assertTrue(c.shouldVibrate());assertFalse(c.canBypassDnd())
        val active=manager.getNotificationChannel(ActiveShiftService.CHANNEL_ID);assertEquals(NotificationManager.IMPORTANCE_LOW,active.importance);assertNull(active.sound)
    }
    @Test fun existingSoundImportanceVibrationPreserved(){
        val manager=context.getSystemService(NotificationManager::class.java);val sound=Uri.parse("content://media/external/audio/media/81")
        manager.createNotificationChannel(NotificationChannel(ShiftNotifications.WEEKLY_CHANNEL,"Weekly hours reminders",NotificationManager.IMPORTANCE_DEFAULT).apply{setSound(sound,null);enableVibration(false)})
        repeat(3){ShiftNotifications(context).createChannels()}
        val c=manager.getNotificationChannel(ShiftNotifications.WEEKLY_CHANNEL);assertEquals(sound,c.sound);assertEquals(NotificationManager.IMPORTANCE_DEFAULT,c.importance);assertFalse(c.shouldVibrate())
    }
    @Test fun shortcutTargetsWeeklyChannel(){val i=ShiftNotifications(context).weeklySettingsIntent();assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,i.action);assertEquals(ShiftNotifications.WEEKLY_CHANNEL,i.getStringExtra(Settings.EXTRA_CHANNEL_ID))}
    @Test fun standardWatchNotificationRequiresNoAction(){
        val now=Instant.parse("2026-10-30T12:00:00Z");val settings=WeeklyTargetSettings()
        val s=WorkSession(id=1,clockIn=now.minusSeconds(38*3600))
        val p=weeklyTarget(listOf(s),emptyList(),settings,60,now,zone)
        val previous=WeeklyWarningLedger(p.week.start,settings,emptySet(),p.correctionKey)
        val d=evaluateWeeklyWarning(p,settings,previous,now)
        val n=ShiftNotifications(context).weeklyReminder(d,p)
        assertEquals(ShiftNotifications.WEEKLY_CHANNEL,n.channelId);assertEquals(Notification.CATEGORY_REMINDER,n.category);assertTrue(n.actions.isNullOrEmpty());assertNotNull(n.contentIntent)
        assertEquals("Weekly target approaching",n.extras.getString(Notification.EXTRA_TITLE));assertNull(n.sound);assertNull(n.vibrate)
        assertEquals(previous.receipt(120),n.extras.getString(ShiftNotifications.WARNING_RECEIPT))
    }
}
