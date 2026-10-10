package com.shifthud.notification

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shifthud.MainActivity
import com.shifthud.R

/** Only implemented production channels. IDs are reserved exclusively for manual tests. */
enum class TestNotificationChannel(val label: String, val channelId: String, val testId: Int, val title: String, val body: String) {
    LUNCH("Lunch reminders", ShiftNotifications.WARNING_CHANNEL, 2001, "ShiftHUD · Lunch Test", "Test lunch alert — check your Garmin."),
    SHIFT_END("Shift end reminders", ShiftNotifications.END_CHANNEL, 2002, "ShiftHUD · Shift End Test", "Test shift-end alert — check your Garmin."),
    WEEKLY("Weekly hours reminders", ShiftNotifications.WEEKLY_CHANNEL, 2003, "ShiftHUD · Weekly Hours Test", "Test 40-hour alert — check your Garmin.")
}
data class NotificationTestStatus(val permissionGranted: Boolean, val appEnabled: Boolean, val channel: ReminderChannelStatus) {
    val blockedReason: String? get() = when {
        !permissionGranted -> "Notification permission is denied. Allow notifications in Android app settings."
        !appEnabled -> "Notifications for ShiftHUD are disabled in Android app settings."
        channel.importance == null -> "This Android channel has not been created. Reopen ShiftHUD, then check notification settings."
        !channel.channelEnabled -> "This notification channel is disabled. Enable it in Android channel settings."
        else -> null
    }
}
data class NotificationTestResult(val posted: Boolean, val message: String)

/** Has no repository, DataStore, shift engine, delivery ledger, or service dependency. */
class NotificationTestCenter(private val context: Context, private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var lastPostedAt: Long? = null
    fun status(channel: TestNotificationChannel): NotificationTestStatus {
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appEnabled = manager.areNotificationsEnabled()
        val actual = manager.getNotificationChannel(channel.channelId)
        return NotificationTestStatus(permission, appEnabled, ReminderChannelStatus(permission && appEnabled,
            actual?.importance, actual?.sound != null, actual?.shouldVibrate() == true))
    }
    fun settingsIntent(channel: TestNotificationChannel): Intent {
        val state = status(channel)
        return if (!state.permissionGranted || !state.appEnabled || state.channel.importance == null)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel.channelId)
    }
    fun notification(channel: TestNotificationChannel): Notification {
        val open = PendingIntent.getActivity(context, channel.testId,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.DESTINATION, "Settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel.channelId).setSmallIcon(R.drawable.ic_stat_shift)
            .setContentTitle(channel.title).setContentText(channel.body).setContentIntent(open)
            .setAutoCancel(true).setOnlyAlertOnce(false).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
    }
    @Synchronized fun post(channel: TestNotificationChannel): NotificationTestResult {
        val state = status(channel)
        state.blockedReason?.let { return NotificationTestResult(false, it) }
        val now = elapsedMillis()
        if (lastPostedAt?.let { now - it < COOLDOWN_MILLIS } == true)
            return NotificationTestResult(false, "Please wait 3 seconds between tests. Android may also limit repeated alerts.")
        return try {
            // Cancel only this channel's manual test so repeated taps are fresh posting events.
            // Other tests and all production notifications remain untouched.
            manager.cancel(channel.testId)
            manager.notify(channel.testId, notification(channel))
            lastPostedAt = now
            NotificationTestResult(true, "Notification posted to Android. Check your Garmin. Watch delivery cannot be confirmed by ShiftHUD.")
        } catch (_: SecurityException) {
            NotificationTestResult(false, "Android blocked notification posting. Check notification permission and channel settings.")
        } catch (_: RuntimeException) {
            NotificationTestResult(false, "Android could not post the test notification. Check notification settings and try again.")
        }
    }
    companion object { const val COOLDOWN_MILLIS = 3000L }
}
