package com.shifthud.notification

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.util.Log
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.shifthud.MainActivity
import com.shifthud.R
import com.shifthud.ShiftHudApplication
import com.shifthud.service.ActiveShiftService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

/** Called through the existing serialized WidgetRefresh path; owns no loop or work-time data. */
class ShiftNotifications(private val context: Context) {
    val testCenter by lazy { NotificationTestCenter(context) }
    private val mutex = Mutex()
    private val manager = context.getSystemService(NotificationManager::class.java)
    @Volatile var nextWeeklyTarget: Instant? = null
        private set
    fun createChannels() {
        manager.createNotificationChannel(NotificationChannel(ActiveShiftService.CHANNEL_ID, context.getString(R.string.active_shift_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.active_shift_channel_description)
            setSound(null, null)
            enableVibration(false)
        })
        if (manager.getNotificationChannel(WEEKLY_CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(WEEKLY_CHANNEL, "Weekly hours reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Personal weekly paid-hours target reminders"
            enableVibration(true)
        })
        if (manager.getNotificationChannel(END_CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(END_CHANNEL, "Shift end reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Personal scheduled-shift wrap-up reminders"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        if (manager.getNotificationChannel(WARNING_CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(WARNING_CHANNEL, "Lunch reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders for your personal lunch threshold"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }
    fun warningsAllowed(): Boolean = permissionAllowed() && manager.areNotificationsEnabled() &&
        (manager.getNotificationChannel(WARNING_CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE
    private fun permissionAllowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(): PendingIntent = PendingIntent.getActivity(context, 1001,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.DESTINATION, "Dashboard")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(action: LunchAction, sessionId: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, NotificationActionReceiver::class.java).setAction(action.name)
            .setData("shifthud://notification/$sessionId/${action.name}".toUri()).putExtra("sessionId", sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun ongoing(state: ShiftNotificationState? = null, sessionId: Long = 0): Notification {
        val builder = NotificationCompat.Builder(context, ActiveShiftService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shift)
            .setContentTitle(state?.title ?: "ShiftHUD")
            .setContentText(state?.content ?: context.getString(R.string.active_shift_restoring))
            .setContentIntent(open()).setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        state?.let {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText((listOf(it.content) + it.secondary).joinToString("\n")))
            it.action?.let { action -> builder.addAction(0, action.name.replace('_', ' '), action(action, sessionId)) }
        }
        return builder.addAction(0, "OPEN", open()).build()
    }

    fun reminder(sessionId: Long, activeWork: Duration, remaining: Duration, receipt: String): Notification =
        NotificationCompat.Builder(context, WARNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shift).setContentTitle(warningTitle(remaining))
            .setContentText("You've worked ${activeWork.notificationDuration()}.")
            .setContentIntent(open()).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Sound/vibration are owned by the channel. Never override the user's selection here.
            .setTimeoutAfter(remaining.toMillis().coerceAtLeast(1))
            .addExtras(android.os.Bundle().apply { putString(WARNING_RECEIPT, receipt) })
            .addAction(0, "START LUNCH", action(LunchAction.START_LUNCH, sessionId))
            .addAction(0, "OPEN", open()).build()

    fun attentionReminder(sessionId: Long, activeWork: Duration, state: LunchAttention, snoozeMinutes: Int, onlyAlertOnce: Boolean = false): Notification {
        val snooze = PendingIntent.getBroadcast(context, 0,
            Intent(context, NotificationActionReceiver::class.java).setAction(SNOOZE_ACTION)
                .setData("shifthud://notification/${state.receipt}/snooze".toUri())
                .putExtra("sessionId", sessionId).putExtra("receipt", state.receipt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, WARNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shift)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setContentTitle(if (state.generation == 0L) "Lunch time" else "Lunch reminder")
            .setContentText(if (state.generation == 0L) "You've worked ${activeWork.notificationDuration()}."
                else "Snoozed reminder · Worked ${activeWork.notificationDuration()}.")
            .setContentIntent(open()).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addExtras(android.os.Bundle().apply { putString(WARNING_RECEIPT, state.receipt) })
            .addAction(0, "START LUNCH", action(LunchAction.START_LUNCH, sessionId))
            .addAction(0, "SNOOZE ${snoozeMinutes}M", snooze)
            .addAction(0, "OPEN", open()).build()
    }

    suspend fun snooze(sessionId: Long, receipt: String): Boolean = mutex.withLock {
        val app = context.applicationContext as ShiftHudApplication
        val accepted = app.preferences.snooze(sessionId, receipt, { app.repository.snapshot().session }, Instant.now(), app.engine)
        if (accepted) manager.cancel(WARNING_ID)
        accepted
    }

    suspend fun forgetSession(sessionId: Long) = mutex.withLock {
        val app = context.applicationContext as ShiftHudApplication
        app.preferences.forgetSession(sessionId)
        // Match the complete ID component, never a bare numeric prefix (1 must not match 10).
        manager.activeNotifications.filter {
            it.id in listOf(WARNING_ID, END_ID) && it.notification.extras.getString(WARNING_RECEIPT)?.startsWith("$sessionId:") == true
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun reminderStatus(): ReminderChannelStatus {
        val channel = manager.getNotificationChannel(WARNING_CHANNEL)
        return ReminderChannelStatus(permissionAllowed() && manager.areNotificationsEnabled(),
            channel?.importance, channel?.sound != null, channel?.shouldVibrate() == true)
    }

    fun reminderSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, WARNING_CHANNEL)

    fun weeklyStatus(): ReminderChannelStatus {
        val channel = manager.getNotificationChannel(WEEKLY_CHANNEL)
        return ReminderChannelStatus(permissionAllowed() && manager.areNotificationsEnabled(), channel?.importance,
            channel?.sound != null, channel?.shouldVibrate() == true)
    }
    fun weeklySettingsIntent(): Intent = reminderSettingsIntent().putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, WEEKLY_CHANNEL)
    fun weeklyReminder(decision: WeeklyWarningDecision, progress: com.shifthud.domain.weekly.WeeklyTargetProgress): Notification {
        val text = weeklyWarningText(decision, progress)
        return NotificationCompat.Builder(context, WEEKLY_CHANNEL).setSmallIcon(R.drawable.ic_stat_shift)
            .setContentTitle(text.first).setContentText(text.second).setContentIntent(open()).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addExtras(android.os.Bundle().apply { putString(WARNING_RECEIPT, decision.ledger.receipt(requireNotNull(decision.boundary))) })
            .build()
    }

    fun shiftEndStatus(): ReminderChannelStatus {
        val channel = manager.getNotificationChannel(END_CHANNEL)
        return ReminderChannelStatus(permissionAllowed() && manager.areNotificationsEnabled(),
            channel?.importance, channel?.sound != null, channel?.shouldVibrate() == true)
    }
    fun shiftEndSettingsIntent(): Intent = reminderSettingsIntent()
        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, END_CHANNEL)
    private fun shiftEndAllowed() = shiftEndStatus().let { it.notificationsAllowed && it.importance != null && it.importance != NotificationManager.IMPORTANCE_NONE }

    fun shiftEndReminder(state: ShiftEndDelivery, snoozeMinutes: Int, now: Instant, onlyAlertOnce: Boolean = false): Notification {
        val snooze = PendingIntent.getBroadcast(context, 0,
            Intent(context, NotificationActionReceiver::class.java).setAction(END_SNOOZE_ACTION)
                .setData("shifthud://shift-end/${state.receipt}/snooze".toUri())
                .putExtra("sessionId", state.sessionId).putExtra("receipt", state.receipt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val format = java.time.format.DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", context.resources.configuration.locales[0])
        val content = "Scheduled out · ${state.end.atZone(ZoneId.systemDefault()).format(format)}\n" + when {
            !now.isBefore(state.end) -> "You're still clocked in to ShiftHUD."
            state.generation > 0 -> "Snoozed reminder."
            else -> "Time to wrap up and clean up."
        }
        return NotificationCompat.Builder(context, END_CHANNEL).setSmallIcon(R.drawable.ic_stat_shift)
            .setContentTitle(shiftEndTitle(state, now)).setContentText(content).setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(open()).setAutoCancel(true).setOnlyAlertOnce(onlyAlertOnce)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addExtras(android.os.Bundle().apply { putString(WARNING_RECEIPT, state.receipt) })
            .addAction(0, "SNOOZE ${snoozeMinutes}M", snooze).addAction(0, "OPEN", open()).build()
    }
    suspend fun snoozeEnd(sessionId: Long, receipt: String): Boolean = mutex.withLock {
        val app = context.applicationContext as ShiftHudApplication
        if (!receipt.startsWith("$sessionId:")) return@withLock false
        val accepted = app.preferences.snoozeShiftEnd(receipt, { app.repository.snapshot() }, Instant.now(), ZoneId.systemDefault())
        if (accepted) manager.cancel(END_ID)
        accepted
    }

    suspend fun refresh() = mutex.withLock {
        createChannels()
        val app = context.applicationContext as ShiftHudApplication
        val snapshot = app.repository.snapshot()
        val session = snapshot.session
        val now = Instant.now()
        val weekly = app.preferences.deliverWeeklyWarning(app.repository.weeklySessions(now, ZoneId.systemDefault()), snapshot.schedule, now, ZoneId.systemDefault()) { event, progress ->
            try {
                if (!weeklyStatus().let { it.notificationsAllowed && it.channelEnabled }) false
                else if (manager.activeNotifications.any { it.id == WEEKLY_ID && it.notification.extras.getString(WARNING_RECEIPT) == event.ledger.receipt(event.boundary!!) }) true
                else { manager.notify(WEEKLY_ID, weeklyReminder(event, progress)); true }
            } catch (_: Exception) { false }
        }
        nextWeeklyTarget = weekly.nextTarget
        if (weekly.cancel) manager.cancel(WEEKLY_ID)
        val endDecision = app.preferences.deliverShiftEnd(snapshot, now, ZoneId.systemDefault()) { event, minutes ->
            try {
                if (!shiftEndAllowed()) false
                else if (manager.activeNotifications.any { it.id == END_ID && it.notification.extras.getString(WARNING_RECEIPT) == event.receipt }) true
                else { manager.notify(END_ID, shiftEndReminder(event, minutes, now)); true }
            } catch (_: Exception) { false }
        }
        if (endDecision.cancel) manager.cancel(END_ID)
        endDecision.state?.takeIf { it.posted }?.let { event ->
            val existing = manager.activeNotifications.firstOrNull { it.id == END_ID && it.notification.extras.getString(WARNING_RECEIPT) == event.receipt }
            val minutes = app.preferences.shiftEndSettings.first().snoozeMinutes
            if (shiftEndAllowed() && existing != null && existing.notification.actions?.firstOrNull()?.title?.toString() != "SNOOZE ${minutes}M")
                manager.notify(END_ID, shiftEndReminder(event, minutes, now, onlyAlertOnce = true))
        }
        fun diagnostic(message: String) {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(DIAGNOSTIC_TAG, "session=${session?.id} $message")
        }
        fun permissionState() = "permission=${permissionAllowed()} notificationsEnabled=${manager.areNotificationsEnabled()} channelImportance=${manager.getNotificationChannel(WARNING_CHANNEL)?.importance}"
        val decision = app.preferences.deliverWarning(session, now, app.engine, warningsAllowed(), onEvaluated = { evaluation ->
            val config = evaluation.ledger?.settings
            val elapsed = session?.let { app.engine.durations(it, config?.threshold ?: 360, now).activeWork.toMillis() }
            diagnostic("evaluate activeMs=$elapsed threshold=${config?.threshold} offsets=${config?.offsets?.sorted()} valid=${config?.validOffsets} candidate=${evaluation.candidateOffset} boundaryMinutes=${evaluation.candidateOffset?.let { config!!.threshold - it }} alreadyConsumed=${evaluation.candidateOffset in (evaluation.ledger?.consumed ?: emptySet())} reason=${evaluation.reason} ${permissionState()} notificationId=$WARNING_ID")
        }) { candidate ->
            val offset = requireNotNull(candidate.offset)
            val settings = requireNotNull(candidate.ledger).settings
            val activeSession = requireNotNull(session)
            val receipt = "${activeSession.id}:$offset"
            try {
                // Recheck immediately before posting; blocked delivery remains retryable.
                if (!warningsAllowed()) {
                    diagnostic("notifyInvoked=false result=blocked ${permissionState()}")
                    false
                } else if (manager.activeNotifications.any { it.id == WARNING_ID && it.notification.extras.getString(WARNING_RECEIPT) == receipt }) {
                    // Android already accepted this event before an interrupted DataStore commit.
                    diagnostic("notifyInvoked=false result=existing_receipt receipt=$receipt")
                    true
                } else {
                    val d = app.engine.durations(activeSession, settings.threshold, now)
                    val notification = reminder(activeSession.id, d.activeWork, d.lunchRemaining, receipt)
                    diagnostic("notifyInvoked=true notificationId=$WARNING_ID channel=$WARNING_CHANNEL receipt=$receipt")
                    manager.notify(WARNING_ID, notification)
                    diagnostic("notifyReturned=true result=posted receipt=$receipt")
                    true
                }
            } catch (e: Exception) {
                diagnostic("result=post_failed exception=${e.javaClass.simpleName} retryable=true")
                false
            }
        }
        diagnostic("dedupCommitted=true posted=${decision.posted} consumed=${decision.ledger?.consumed?.sorted()} cancel=${decision.cancel}")
        val attention = app.preferences.deliverAttention(session, now, app.engine, warningsAllowed()) { event, snoozeMinutes ->
            try {
                if (!warningsAllowed()) false
                else if (manager.activeNotifications.any { it.id == WARNING_ID && it.notification.extras.getString(WARNING_RECEIPT) == event.receipt }) true
                else {
                    val active = app.engine.durations(requireNotNull(session), decision.ledger?.settings?.threshold ?: 360, now).activeWork
                    diagnostic("attentionNotifyInvoked=true receipt=${event.receipt} targetActiveMs=${event.targetActiveMillis}")
                    manager.notify(WARNING_ID, attentionReminder(session.id, active, event, snoozeMinutes))
                    diagnostic("attentionNotifyReturned=true receipt=${event.receipt}")
                    true
                }
            } catch (e: Exception) {
                diagnostic("attentionPostFailed=${e.javaClass.simpleName} retryable=true")
                false
            }
        }
        diagnostic("attentionCommitted=true receipt=${attention.state?.receipt} posted=${attention.state?.posted} targetActiveMs=${attention.state?.targetActiveMillis}")
        if (attention.corrected || (decision.cancel && attention.state?.posted != true)) manager.cancel(WARNING_ID)
        // A changed preference updates an existing action label silently, never creates another alert.
        attention.state?.takeIf { it.posted }?.let { event ->
            val existing = manager.activeNotifications.firstOrNull {
                it.id == WARNING_ID && it.notification.extras.getString(WARNING_RECEIPT) == event.receipt
            }
            val minutes = app.preferences.snoozeMinutes.first()
            if (warningsAllowed() && existing != null && existing.notification.actions?.getOrNull(1)?.title?.toString() != "SNOOZE ${minutes}M") {
                val active = app.engine.durations(requireNotNull(session), decision.ledger?.settings?.threshold ?: 360, now).activeWork
                manager.notify(WARNING_ID, attentionReminder(session.id, active, event, minutes, onlyAlertOnce = true))
            }
        }
        val settings = decision.ledger?.settings ?: app.preferences.warningSettings.first()
        val state = ShiftNotificationStateFactory(app.engine).create(snapshot, settings.threshold, now,
            ZoneId.systemDefault(), context.resources.configuration.locales[0], android.text.format.DateFormat.is24HourFormat(context))
        if (state == null) manager.cancel(ActiveShiftService.NOTIFICATION_ID)
        else if (ActiveShiftService.isRunning && permissionAllowed()) manager.notify(ActiveShiftService.NOTIFICATION_ID, ongoing(state, session!!.id))
    }

    companion object {
        const val WEEKLY_CHANNEL = "weekly_hours_reminders"
        const val WEEKLY_ID = 1004
        const val END_CHANNEL = "shift_end_reminders"
        const val END_ID = 1003
        const val END_SNOOZE_ACTION = "SNOOZE_SHIFT_END"
        const val SNOOZE_ACTION = "SNOOZE_LUNCH"
        const val DIAGNOSTIC_TAG = "ShiftHUDWarning"
        const val WARNING_RECEIPT = "shifthud.warning.receipt"
        const val WARNING_CHANNEL = "lunch_reminders"
        const val WARNING_ID = 1002
    }
}
