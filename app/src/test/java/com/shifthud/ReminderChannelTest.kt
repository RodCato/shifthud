package com.shifthud

import android.app.*
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.shifthud.notification.*
import com.shifthud.service.ActiveShiftService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReminderChannelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @org.junit.Before fun allowNotifications() {
        Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun freshChannelsHaveDistinctAttentionAndSilentDefaults() {
        ShiftNotifications(context).createChannels()
        val reminder = manager.getNotificationChannel(ShiftNotifications.WARNING_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, reminder.importance)
        assertNotNull(reminder.sound)
        assertTrue(reminder.shouldVibrate())
        assertFalse(reminder.canBypassDnd())
        assertEquals(Notification.VISIBILITY_PUBLIC, reminder.lockscreenVisibility)
        val ongoing = manager.getNotificationChannel(ActiveShiftService.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoing.importance)
        assertNull(ongoing.sound)
        assertFalse(ongoing.shouldVibrate())
    }
    @Test fun existingDefaultChannelAndCustomSoundSurviveRepeatedStartup() {
        val custom = Uri.parse("content://media/external/audio/media/42")
        manager.createNotificationChannel(NotificationChannel(ShiftNotifications.WARNING_CHANNEL, "Lunch reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(custom, null); enableVibration(false)
        })
        val notifications = ShiftNotifications(context)
        repeat(3) { notifications.createChannels() }
        val channel = manager.getNotificationChannel(ShiftNotifications.WARNING_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(custom, channel.sound)
        assertFalse(channel.shouldVibrate())
        val status = notifications.reminderStatus()
        assertEquals("Default", status.importanceLabel)
        assertTrue(status.soundConfigured)
        assertTrue(status.advice!!.contains("not High"))
    }
    @Test fun disabledSilentChannelIsPreservedAndReported() {
        manager.createNotificationChannel(NotificationChannel(ShiftNotifications.WARNING_CHANNEL, "Lunch reminders", NotificationManager.IMPORTANCE_NONE).apply { setSound(null, null) })
        val notifications = ShiftNotifications(context)
        notifications.createChannels()
        val status = notifications.reminderStatus()
        assertEquals("Disabled", status.importanceLabel)
        assertFalse(status.channelEnabled)
        assertFalse(status.soundConfigured)
        assertFalse(notifications.warningsAllowed())
        assertTrue(status.advice!!.contains("disabled"))
    }
    @Test fun silentEnabledChannelReportsNoSound() {
        manager.createNotificationChannel(NotificationChannel(ShiftNotifications.WARNING_CHANNEL, "Lunch reminders", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val status = ShiftNotifications(context).reminderStatus()
        assertTrue(status.channelEnabled)
        assertEquals("No sound is configured for Lunch reminders.", status.advice)
    }
    @Test fun shortcutTargetsReminderChannel() {
        val intent = ShiftNotifications(context).reminderSettingsIntent()
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(ShiftNotifications.WARNING_CHANNEL, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
    @Test fun reminderUsesChannelOwnedSoundAndReminderSemantics() {
        val n = ShiftNotifications(context).reminder(99, Duration.ofMinutes(5), Duration.ofMinutes(5), "99:5")
        assertEquals(ShiftNotifications.WARNING_CHANNEL, n.channelId)
        assertNotEquals(ActiveShiftService.CHANNEL_ID, n.channelId)
        assertNull(n.sound)
        assertNull(n.vibrate)
        assertEquals(0, n.defaults)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        assertEquals(NotificationCompat.PRIORITY_HIGH, n.priority)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("START LUNCH", n.actions[0].title)
        assertTrue(n.actions[0].actionIntent.isImmutable)
        assertEquals(99L, Shadows.shadowOf(n.actions[0].actionIntent).savedIntent.getLongExtra("sessionId", 0))
    }
}
