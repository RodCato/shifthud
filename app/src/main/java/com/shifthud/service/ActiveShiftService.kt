package com.shifthud.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shifthud.MainActivity
import com.shifthud.R
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.ShiftState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Refresh driver only: it reads persisted timestamps and never writes a WorkSession. */
class ActiveShiftService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var latestStartId = 0
    private var notificationState: ShiftState? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.active_shift_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.active_shift_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // Promote immediately, before any Room I/O, including a sticky null-intent restart.
        showNotification(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        wake.trySend(Unit)
        if (loop?.isActive != true) loop = scope.launch { refreshWhileActive() }
        return START_STICKY
    }

    private suspend fun refreshWhileActive() {
        val app = application as ShiftHudApplication
        while (currentCoroutineContext().isActive) {
            while (wake.tryReceive().isSuccess) { /* Coalesce repeated starts; keep one loop. */ }
            val observedStartId = latestStartId
            try {
                val session = app.repository.snapshot().session
                if (!requiresActiveRefresh(session?.state)) {
                    app.widgetRefresh.refresh(mayStartService = false, synchronizeService = false)
                    if (stopSelfResult(observedStartId)) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        return
                    }
                    continue // A newer user action arrived during the final refresh; reread Room.
                }
                requireNotNull(session)
                if (notificationState != session.state) showNotification(session.state)
                // Checks for installed instances before touching Glance. No timer writes, wake lock,
                // exact alarms, or catch-up ticks. After sleep/Doze, the next render uses current time.
                app.widgetRefresh.refresh(mayStartService = false, synchronizeService = false)
                val durations = app.engine.durations(session, 360)
                val elapsed = if (session.state == ShiftState.ON_LUNCH) durations.lunch else durations.activeWork
                withTimeoutOrNull(nextActiveRefreshDelayMillis(elapsed)) { wake.receive() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Refresh delayed; persisted session is unchanged", e)
                withTimeoutOrNull(60_000L) { wake.receive() }
            }
        }
    }

    private fun showNotification(state: ShiftState?) {
        val open = PendingIntent.getActivity(this, 1001,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.DESTINATION, "Dashboard")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when (state) {
            ShiftState.WORKING -> R.string.active_shift_working
            ShiftState.ON_LUNCH -> R.string.active_shift_lunch
            else -> R.string.active_shift_restoring
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shift)
            .setContentTitle("ShiftHUD")
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        notificationState = state
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        wake.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "active_shift_updates"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "ActiveShiftService"
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ActiveShiftService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+ can deny background starts. Keep persistence/manual/periodic refresh
                // intact; the next visible app launch or widget action can legally retry.
                Log.w(TAG, "Android denied service start; open ShiftHUD to resume live refresh", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Service start unavailable; keeping widget fallback", e)
            }
        }
        fun stop(context: Context) { context.stopService(Intent(context, ActiveShiftService::class.java)) }
    }
}
