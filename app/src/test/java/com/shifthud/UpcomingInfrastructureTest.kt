package com.shifthud

import android.Manifest
import android.app.*
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import com.shifthud.domain.model.ScheduledShift
import com.shifthud.notification.*
import com.shifthud.notification.upcoming.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class UpcomingInfrastructureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var now=LocalDate.now().atTime(21,0).atZone(ZoneId.systemDefault()).toInstant()
    private val clock=object: java.time.Clock() { override fun instant()=now;override fun getZone()=ZoneId.systemDefault();override fun withZone(zone: ZoneId)=this }
    private var rows=listOf(ScheduledShift(date=LocalDate.now().plusDays(1),scheduledStart=LocalTime.of(5,0),scheduledEnd=LocalTime.of(14,0)))
    private val pending=mutableListOf<Pair<Instant,ExistingWorkPolicy>>()
    private fun reminders()=UpcomingReminders(context,{rows},clock){at,policy->pending.add(at to policy)}
    @Before fun setup() { context.getSharedPreferences("upcoming_reminders",0).edit().clear().commit();Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) }
    @Test fun settingsPersistAcrossRecreation()=runBlocking { val r=reminders();r.configure(UpcomingSettings(time=LocalTime.of(19,15),daysOff=true));assertEquals(LocalTime.of(19,15),reminders().store.settings().time);assertTrue(reminders().store.settings().daysOff);assertEquals(ExistingWorkPolicy.REPLACE,pending.last().second) }
    @Test fun deliveryAcknowledgesAndRecreationDoesNotDuplicate()=runBlocking { val r=reminders();r.deliverAndSchedule();val n=manager.activeNotifications.single().notification;assertNotNull(r.store.receipt());reminders().deliverAndSchedule();assertSame(n,manager.activeNotifications.single().notification);assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE,pending.last().second) }
    @Test fun blockedPermissionNotAcknowledgedAndRetries()=runBlocking { Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);val r=reminders();r.deliverAndSchedule();assertNull(r.store.receipt());assertTrue(manager.activeNotifications.isEmpty());assertEquals(now.plusSeconds(900),pending.last().first) }
    @Test fun blockedChannelNotAcknowledged()=runBlocking { manager.createNotificationChannel(NotificationChannel(UpcomingReminders.CHANNEL,"Upcoming",0));val r=reminders();r.deliverAndSchedule();assertNull(r.store.receipt());assertTrue(manager.activeNotifications.isEmpty()) }
    @Test fun customChannelSettingsNeverOverridden()=runBlocking { val sound=Uri.parse("content://media/external/audio/media/98");manager.createNotificationChannel(NotificationChannel(UpcomingReminders.CHANNEL,"Upcoming",3).apply{setSound(sound,null);enableVibration(false)});reminders().deliverAndSchedule();val c=manager.getNotificationChannel(UpcomingReminders.CHANNEL);assertEquals(sound,c.sound);assertEquals(3,c.importance);assertFalse(c.shouldVibrate());assertFalse(c.canBypassDnd());val n=manager.activeNotifications.single().notification;assertNull(n.sound);assertEquals(Notification.CATEGORY_REMINDER,n.category) }
    @Test fun channelFreshImportanceHigh() { UpcomingReminders.createChannel(context);assertEquals(4,manager.getNotificationChannel(UpcomingReminders.CHANNEL).importance) }
    @Test fun testUsesRealScheduleWithoutDeliveryAcknowledgement()=runBlocking { UpcomingReminders.createChannel(context);val r=reminders();assertTrue(r.test(NotificationTestCenter(context)).posted);val n=manager.activeNotifications.single();assertEquals(2004,n.id);assertEquals(UpcomingReminders.CHANNEL,n.notification.channelId);assertTrue(n.notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("5:00"));assertNull(r.store.receipt());assertEquals(1,rows.size);assertTrue(pending.isEmpty()) }
    @Test fun emptyTestClearlyLabelsSample()=runBlocking { rows=emptyList();UpcomingReminders.createChannel(context);reminders().test(NotificationTestCenter(context));assertTrue(manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("Sample only"));assertTrue(rows.isEmpty()) }
    @Test fun editThenDeletionPostsUpdatedCurrentState()=runBlocking { val r=reminders();r.deliverAndSchedule();now=now.plusSeconds(121);rows=listOf(rows.single().copy(scheduledStart=LocalTime.of(6,0)));r.deliverAndSchedule();assertTrue(manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("6:00"));now=now.plusSeconds(121);rows=emptyList();r.deliverAndSchedule();assertTrue(manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("removed")) }
    @Test fun disabledCancelsAndNoSuccessor()=runBlocking { val r=reminders();r.deliverAndSchedule();r.configure(UpcomingSettings(enabled=false));pending.clear();r.deliverAndSchedule();assertTrue(manager.activeNotifications.isEmpty());assertTrue(pending.isEmpty()) }
    @Test fun recoveryActionsAndDebounce()=runBlocking { assertEquals(setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED),UpcomingRecoveryReceiver.ACTIONS);reminders().reschedule(true);assertEquals(now.plusSeconds(30),pending.last().first);reminders().reschedule();assertEquals(now,pending.last().first) }
    @Test fun reminderExpiresAtLocalMidnight() { val n=UpcomingReminders.buildNotification(context,upcomingContent(rows.single().date,rows),now);assertEquals(3*3600_000L,n.timeoutAfter) }
    @Test fun productionTapOpensScheduleAndNoEmployerClaim() { val n=UpcomingReminders.buildNotification(context,upcomingContent(rows.single().date,rows));assertEquals("Schedule",Shadows.shadowOf(n.contentIntent).savedIntent.getStringExtra(MainActivity.DESTINATION));assertTrue(n.contentIntent.isImmutable);assertFalse(n.extras.toString().contains("Publix")) }
    @Test fun shadeReceiptRecoversInterruptedAcknowledgement()=runBlocking { val r=reminders();r.deliverAndSchedule();val first=manager.activeNotifications.single().notification;context.getSharedPreferences("upcoming_reminders",0).edit().clear().commit();reminders().deliverAndSchedule();assertSame(first,manager.activeNotifications.single().notification);assertNotNull(r.store.receipt()) }
}
