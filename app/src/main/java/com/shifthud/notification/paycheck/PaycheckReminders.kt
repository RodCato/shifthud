package com.shifthud.notification.paycheck

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.shifthud.MainActivity
import com.shifthud.R
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.payroll.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import com.shifthud.backup.guardedLock as withLock
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

data class PaycheckReminderSettings(val enabled: Boolean = true, val day: DayOfWeek = DayOfWeek.THURSDAY, val time: LocalTime = LocalTime.of(19,0))
fun nextPaycheckReminder(now: Instant, zone: ZoneId, settings: PaycheckReminderSettings): Instant {
    val local = now.atZone(zone)
    var date = local.toLocalDate().with(TemporalAdjusters.nextOrSame(settings.day))
    if (!date.atTime(settings.time).atZone(zone).toInstant().isAfter(now)) date = date.plusWeeks(1)
    return date.atTime(settings.time).atZone(zone).toInstant()
}
/** Narrow suppression: saved/reviewed complete payroll dated exactly on this reminder day.
 * No inferred employer cadence, payroll availability, or confirmation writes. */
fun paycheckAlreadyRecorded(records: List<Paycheck>, date: LocalDate): Boolean = earningsOverview(records).checks.any {
    it.payDate == date && it.periodEnd < date && it.grossCents != null && it.netCents != null &&
        it.reportedHours != null && calculatedCashNet(it) != null && cashNetDifference(it) == 0L
}
@android.annotation.SuppressLint("UseKtx")
class PaycheckReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("paycheck_import_reminder",Context.MODE_PRIVATE)
    fun settings() = PaycheckReminderSettings(prefs.getBoolean("enabled",true),DayOfWeek.of(prefs.getInt("day",4).coerceIn(1,7)),LocalTime.ofSecondOfDay(prefs.getInt("minute",1140).coerceIn(0,1439)*60L))
    fun save(s: PaycheckReminderSettings) { check(prefs.edit().putBoolean("enabled",s.enabled).putInt("day",s.day.value).putInt("minute",s.time.hour*60+s.time.minute).commit()) }
    fun receipt(): LocalDate? = prefs.getString("delivered",null)?.let(LocalDate::parse)
    fun acknowledge(date: LocalDate) { check(prefs.edit().putString("delivered",date.toString()).commit()) }
}
class PaycheckReminders(private val context: Context, private val records: suspend () -> List<Paycheck>, private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val enqueueWork: ((Instant?,ExistingWorkPolicy)->Unit)? = null) {
    val store = PaycheckReminderStore(context)
    private val mutex = Mutex()
    private val manager = context.getSystemService(NotificationManager::class.java)
    suspend fun configure(settings: PaycheckReminderSettings) = withContext(Dispatchers.IO + NonCancellable) { mutex.withLock {
        store.save(settings)
        manager.cancel(ID)
        enqueue(if(settings.enabled) nextPaycheckReminder(clock.instant(),ZoneId.systemDefault(),settings) else null,ExistingWorkPolicy.REPLACE)
    } }
    suspend fun reschedule() = withContext(Dispatchers.IO) { mutex.withLock {
        enqueue(if(store.settings().enabled) clock.instant() else null,ExistingWorkPolicy.REPLACE)
    } }
    private fun enqueue(at: Instant?, policy: ExistingWorkPolicy) {
        enqueueWork?.let { it(at,policy);return }
        val wm = WorkManager.getInstance(context)
        if(at==null) { wm.cancelUniqueWork(WORK).result.get();return }
        val request = OneTimeWorkRequestBuilder<PaycheckReminderWorker>().setInitialDelay(Duration.between(clock.instant(),at).toMillis().coerceAtLeast(0),TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork(WORK,policy,request).result.get()
    }
    fun blockedReason(): String? = when {
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED -> "Allow notifications to receive paycheck reminders."
        !manager.areNotificationsEnabled() -> "ShiftHUD notifications are disabled."
        manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> "Paycheck import notifications are disabled."
        else -> null
    }
    fun settingsIntent(): Intent = if(blockedReason()!=null && (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled()))
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
        else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,CHANNEL)
    suspend fun deliverAndSchedule() = withContext(Dispatchers.IO) { mutex.withLock {
        createChannel(context)
        val settings = store.settings()
        if(!settings.enabled) { manager.cancel(ID);return@withLock }
        val now = clock.instant()
        val zone = ZoneId.systemDefault()
        val local = now.atZone(zone)
        var date = local.toLocalDate().with(TemporalAdjusters.previousOrSame(settings.day))
        if(date.atTime(settings.time).atZone(zone).toInstant()>now) date=date.minusWeeks(1)
        val due = date.atTime(settings.time).atZone(zone).toInstant()
        var next = nextPaycheckReminder(now,zone,settings)
        // Recover notification posting interrupted before durable acknowledgement.
        manager.activeNotifications.firstOrNull { it.id==ID }?.notification?.extras?.getString(RECEIPT)?.let {
            val recovered=runCatching { LocalDate.parse(it) }.getOrNull()
            if(recovered!=null && (store.receipt()==null || recovered>store.receipt())) store.acknowledge(recovered)
        }
        if(Duration.between(due,now)<Duration.ofDays(1) && store.receipt()?.let { it>=date }!=true) {
            if(paycheckAlreadyRecorded(records(),date)) store.acknowledge(date)
            else if(blockedReason()==null) {
                try {
                    manager.cancel(ID)
                    manager.notify(ID,notification(context).apply { extras.putString(RECEIPT,date.toString()) })
                    store.acknowledge(date)
                } catch (_: SecurityException) { next=minOf(next,now.plusSeconds(900)) }
            } else next=minOf(next,now.plusSeconds(900))
        }
        enqueue(next,ExistingWorkPolicy.APPEND_OR_REPLACE)
    } }
    companion object {
        const val CHANNEL="paycheck_import_reminders"
        const val ID=1006
        const val WORK="paycheck-import-reminder"
        const val RECEIPT="paycheck_reminder_date"
        const val DESTINATION="Paychecks"
        fun createChannel(context: Context) {
            val manager=context.getSystemService(NotificationManager::class.java)
            if(manager.getNotificationChannel(CHANNEL)==null) manager.createNotificationChannel(NotificationChannel(CHANNEL,"Paycheck import reminders",NotificationManager.IMPORTANCE_DEFAULT))
        }
        fun notification(context: Context): Notification {
            val tap=PendingIntent.getActivity(context,ID,Intent(context,MainActivity::class.java).putExtra(MainActivity.DESTINATION,DESTINATION).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text="Your latest paystub may be available. Add it to ShiftHUD and reconcile your earnings."
            return NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_shift).setContentTitle("Import Publix Paycheck").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_REMINDER).build()
        }
    }
}
class PaycheckReminderWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try { (applicationContext as ShiftHudApplication).paycheckReminders.deliverAndSchedule();Result.success() }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { Result.retry() }
}
class PaycheckReminderRecoveryReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in ACTIONS)return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { withTimeout(8000) { (context.applicationContext as ShiftHudApplication).paycheckReminders.reschedule() } }
            catch(_: Exception) { /* Persisted WorkManager job or next app opening retries recovery. */ }
            finally { pending.finish() }
        }
    }
    companion object { val ACTIONS=setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED) }
}
