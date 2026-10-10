package com.shifthud

import android.Manifest
import android.app.*
import android.net.Uri
import android.provider.Settings
import com.shifthud.notification.*
import com.shifthud.service.ActiveShiftService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class NotificationTestCenterTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var now=0L
    private lateinit var center: NotificationTestCenter
    @Before fun setup() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShiftNotifications(context).createChannels()
        center=NotificationTestCenter(context){now}
    }
    @Test fun onlyImplementedChannelsAndExactMapping() {
        assertEquals(listOf("lunch_reminders","shift_end_reminders","weekly_hours_reminders"),TestNotificationChannel.entries.map{it.channelId})
        TestNotificationChannel.entries.forEach { assertEquals(it.channelId,center.notification(it).channelId) }
    }
    @Test fun idsAreDistinctAndNeverProduction() {
        val ids=TestNotificationChannel.entries.map{it.testId}
        assertEquals(ids.size,ids.toSet().size)
        assertTrue(ids.none{it in setOf(ActiveShiftService.NOTIFICATION_ID,ShiftNotifications.WARNING_ID,ShiftNotifications.END_ID,ShiftNotifications.WEEKLY_ID)})
    }
    @Test fun realPostsRemainObservableAcrossChannels() {
        TestNotificationChannel.entries.forEach { assertTrue(center.post(it).posted);now+=3000 }
        assertEquals(TestNotificationChannel.entries.map{it.testId}.toSet(),manager.activeNotifications.map{it.id}.toSet())
    }
    @Test fun exactContentAndStandardReminderSemantics() {
        TestNotificationChannel.entries.forEach {
            val n=center.notification(it)
            assertEquals(it.title,n.extras.getString(Notification.EXTRA_TITLE));assertEquals(it.body,n.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(Notification.CATEGORY_REMINDER,n.category);assertNull(n.sound);assertNull(n.vibrate);assertEquals(0,n.defaults)
            assertEquals(0,n.flags and Notification.FLAG_ONLY_ALERT_ONCE);assertTrue(n.actions.isNullOrEmpty())
            assertFalse(n.extras.containsKey(ShiftNotifications.WARNING_RECEIPT))
        }
    }
    @Test fun ongoingAndProductionRemindersUntouched() {
        val productionIds=listOf(1001,1002,1003,1004)
        productionIds.forEach { manager.notify(it,ShiftNotifications(context).ongoing()) }
        val before=manager.activeNotifications.associate{it.id to it.notification}
        TestNotificationChannel.entries.forEach {center.post(it);now+=3000}
        productionIds.forEach { id->assertEquals(before[id],manager.activeNotifications.single{it.id==id}.notification) }
    }
    @Test fun repeatedTestsAreFreshPostsAndBounded() {
        val channel=TestNotificationChannel.LUNCH
        assertTrue(center.post(channel).posted)
        val first=manager.activeNotifications.single().notification
        now=3000;assertTrue(center.post(channel).posted)
        val second=manager.activeNotifications.single().notification
        assertNotSame(first,second);assertEquals(channel.testId,manager.activeNotifications.single().id)
    }
    @Test fun rapidRepeatIsThrottledWithoutReplacingExistingTest() {
        assertTrue(center.post(TestNotificationChannel.LUNCH).posted)
        now=2999;val r=center.post(TestNotificationChannel.WEEKLY);assertFalse(r.posted);assertTrue(r.message.contains("3 seconds"))
        assertEquals(1,manager.activeNotifications.size)
        now=3000;assertTrue(center.post(TestNotificationChannel.WEEKLY).posted)
    }
    @Test fun permissionDeniedExplainsAndOpensAppSettings() {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val r=center.post(TestNotificationChannel.LUNCH);assertFalse(r.posted);assertTrue(r.message.contains("permission"));assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,center.settingsIntent(TestNotificationChannel.LUNCH).action)
    }
    @Test fun appWideNotificationsDisabled() {
        Shadows.shadowOf(manager).setNotificationsEnabled(false)
        val r=center.post(TestNotificationChannel.LUNCH);assertFalse(r.posted);assertTrue(r.message.contains("disabled"));assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun missingChannelIsNotFabricated() {
        manager.deleteNotificationChannel(ShiftNotifications.END_CHANNEL)
        val r=center.post(TestNotificationChannel.SHIFT_END);assertFalse(r.posted);assertTrue(r.message.contains("not been created"))
        assertNull(manager.getNotificationChannel(ShiftNotifications.END_CHANNEL));assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,center.settingsIntent(TestNotificationChannel.SHIFT_END).action)
    }
    @Test fun tapOpensSettingsWithImmutableIntent() {
        TestNotificationChannel.entries.forEach {
            val n=center.notification(it);val intent=Shadows.shadowOf(n.contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name,intent.component!!.className);assertEquals("Settings",intent.getStringExtra(MainActivity.DESTINATION));assertTrue(n.contentIntent.isImmutable)
        }
    }
    @Test fun resultDoesNotClaimWatchDelivery() {
        val r=center.post(TestNotificationChannel.LUNCH)
        assertTrue(r.message.contains("Notification posted to Android"));assertTrue(r.message.contains("cannot be confirmed"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class NotificationTestChannelPreservationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun permission(){Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)}
    @Test fun blockedChannelExplainsAndTargetsSpecificSettings() {
        manager.createNotificationChannel(NotificationChannel(ShiftNotifications.WARNING_CHANNEL,"Lunch reminders",NotificationManager.IMPORTANCE_NONE))
        val center=NotificationTestCenter(context)
        assertFalse(center.status(TestNotificationChannel.LUNCH).channel.channelEnabled)
        assertFalse(center.post(TestNotificationChannel.LUNCH).posted)
        val i=center.settingsIntent(TestNotificationChannel.LUNCH)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,i.action);assertEquals(ShiftNotifications.WARNING_CHANNEL,i.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
    @Test fun customSoundAndVibrationPreserved() {
        val sound=Uri.parse("content://media/external/audio/media/91")
        manager.createNotificationChannel(NotificationChannel(ShiftNotifications.WEEKLY_CHANNEL,"Weekly hours reminders",NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(sound,null);enableVibration(false)
        })
        val before=manager.getNotificationChannel(ShiftNotifications.WEEKLY_CHANNEL)
        val center=NotificationTestCenter(context)
        assertTrue(center.post(TestNotificationChannel.WEEKLY).posted)
        assertEquals(before,manager.getNotificationChannel(before.id));assertEquals(sound,manager.getNotificationChannel(before.id).sound)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,center.status(TestNotificationChannel.WEEKLY).channel.importance)
    }
}
