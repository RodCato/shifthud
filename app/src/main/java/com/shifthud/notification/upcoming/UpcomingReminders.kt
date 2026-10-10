package com.shifthud.notification.upcoming

import android.app.*
import android.content.*
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.shifthud.MainActivity
import com.shifthud.R
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import com.shifthud.backup.guardedLock as withLock
import java.time.*
import java.util.concurrent.TimeUnit

/** Dedicated metadata only; never writes schedule/session rows or other reminder ledgers. */
// Synchronous commit confirms durable acknowledgement; the KTX edit helper discards its result.
@android.annotation.SuppressLint("UseKtx")
class UpcomingStore(context: Context) {
    private val prefs = context.getSharedPreferences("upcoming_reminders", Context.MODE_PRIVATE)
    fun settings() = UpcomingSettings(prefs.getBoolean("enabled", true), LocalTime.ofSecondOfDay(prefs.getInt("minute", 1200).coerceIn(0,1439) * 60L), prefs.getBoolean("days_off", false))
    fun save(settings: UpcomingSettings) { check(prefs.edit().putBoolean("enabled", settings.enabled).putInt("minute", settings.time.hour*60+settings.time.minute).putBoolean("days_off", settings.daysOff).commit()) }
    fun receipt(): UpcomingReceipt? = runCatching { UpcomingReceipt(LocalDate.parse(prefs.getString("date", null)), prefs.getString("state", null)!!, Instant.ofEpochMilli(prefs.getLong("posted", 0))) }.getOrNull()
    fun acknowledge(receipt: UpcomingReceipt) { check(prefs.edit().putString("date", receipt.date.toString()).putString("state", receipt.fingerprint).putLong("posted", receipt.postedAt.toEpochMilli()).commit()) }
}
class UpcomingReminders(private val context: Context, private val schedule: suspend () -> List<com.shifthud.domain.model.ScheduledShift>, private val clock: java.time.Clock = java.time.Clock.systemUTC(), private val enqueueWork: ((Instant, ExistingWorkPolicy) -> Unit)? = null) {
    val store = UpcomingStore(context)
    private val mutex = Mutex()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val tests = NotificationTestCenter(context)
    private fun now() = clock.instant()
    suspend fun configure(settings: UpcomingSettings) = withContext(Dispatchers.IO + NonCancellable) { mutex.withLock {
        store.save(settings)
        if (!settings.enabled) manager.cancel(ID)
        enqueue(now(), ExistingWorkPolicy.REPLACE)
    } }
    suspend fun reschedule(debounce: Boolean = false) = withContext(Dispatchers.IO) { mutex.withLock { enqueue(now().plusSeconds(if (debounce) 30 else 0), ExistingWorkPolicy.REPLACE) } }
    private fun enqueue(at: Instant, policy: ExistingWorkPolicy) {
        enqueueWork?.let { it(at, policy); return }
        val request = OneTimeWorkRequestBuilder<UpcomingReminderWorker>().setInitialDelay(Duration.between(now(), at).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request).result.get()
    }
    /** Worker reevaluates Room and local time, so stale queued work never carries stale schedule content. */
    suspend fun deliverAndSchedule() = withContext(Dispatchers.IO) { mutex.withLock {
        createChannel(context)
        val settings = store.settings()
        if (!settings.enabled) { manager.cancel(ID); return@withLock }
        val instant = now()
        // Reconcile a crash after notify but before metadata acknowledgement, when still in the shade.
        manager.activeNotifications.firstOrNull { it.id == ID }?.notification?.extras?.let { extras ->
            val recovered = runCatching { UpcomingReceipt(LocalDate.parse(extras.getString("upcoming_date")), extras.getString("upcoming_state")!!, Instant.ofEpochMilli(extras.getLong("upcoming_posted"))) }.getOrNull()
            if (recovered != null && (store.receipt()?.postedAt ?: Instant.MIN) < recovered.postedAt) store.acknowledge(recovered)
        }
        val plan = upcomingPlan(instant, ZoneId.systemDefault(), settings, schedule(), store.receipt(), DateFormat.is24HourFormat(context))
        var next = plan.next
        plan.content?.let { content ->
            if (tests.status(TestNotificationChannel.UPCOMING).blockedReason == null) {
                val notification = buildNotification(context, content, instant)
                notification.extras.putString("upcoming_date", plan.date.toString())
                notification.extras.putString("upcoming_state", plan.fingerprint)
                notification.extras.putLong("upcoming_posted", instant.toEpochMilli())
                try {
                    manager.notify(ID, notification)
                    store.acknowledge(UpcomingReceipt(plan.date, plan.fingerprint, instant))
                } catch (_: SecurityException) { next = retryAt(instant, next) }
            } else next = retryAt(instant, next)
        }
        // Append successor so this running WorkRequest is never cancelled by its own rescheduling.
        enqueue(next, ExistingWorkPolicy.APPEND_OR_REPLACE)
    } }
    private fun retryAt(instant: Instant, next: Instant) = minOf(instant.plusSeconds(900), next)
    suspend fun test(center: NotificationTestCenter): NotificationTestResult {
        val tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1)
        val shifts = tomorrowShifts(schedule(), tomorrow.minusDays(1))
        val content = if (shifts.isEmpty()) UpcomingContent("ShiftHUD · Tomorrow Test", "Sample only · Tomorrow at 5:00 AM–2:00 PM")
            else upcomingContent(tomorrow, shifts, is24Hour = DateFormat.is24HourFormat(context)).let { it.copy(title = "ShiftHUD · Tomorrow Test", expanded = it.expanded, text = if (shifts.size > 1) it.expanded.lineSequence().first() + " · ${shifts.size} shifts" else it.text) }
        return center.post(TestNotificationChannel.UPCOMING, content)
    }
    companion object {
        const val CHANNEL = "upcoming_shift_reminders"
        const val ID = 1005
        const val WORK = "upcoming-shift-reminder"
        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Upcoming shift reminders", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
        fun buildNotification(context: Context, content: UpcomingContent, now: Instant = Instant.now()): Notification {
            val tap = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java).putExtra(MainActivity.DESTINATION, "Schedule"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_shift).setContentTitle(content.title).setContentText(content.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded)).setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(false)
                .setTimeoutAfter(Duration.between(now, now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()).toMillis().coerceAtLeast(1))
                .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        }
    }
}
class UpcomingReminderWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { (applicationContext as ShiftHudApplication).upcoming.deliverAndSchedule(); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
}
class UpcomingRecoveryReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8000) { (context.applicationContext as ShiftHudApplication).upcoming.reschedule() } }
            catch (e: Exception) { android.util.Log.w("UpcomingReminder", "Recovery deferred to persisted work or app reopening", e) }
            finally { pending.finish() }
        }
    }
    companion object { val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED) }
}
