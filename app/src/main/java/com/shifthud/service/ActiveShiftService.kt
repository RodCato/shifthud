package com.shifthud.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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

    override fun onCreate() {
        super.onCreate()
        val notifications = (application as ShiftHudApplication).notifications
        notifications.createChannels()
        // Promote before Room I/O, including sticky null-intent recovery.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notifications.ongoing(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        isRunning = true
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
                // Checks for installed instances before touching Glance. No timer writes, wake lock,
                // exact alarms, or catch-up ticks. After sleep/Doze, the next render uses current time.
                app.widgetRefresh.refresh(mayStartService = false, synchronizeService = false)
                val refreshed = app.repository.snapshot().session ?: continue
                val now = java.time.Instant.now()
                val durations = app.engine.durations(refreshed, 360, now)
                val elapsed = if (refreshed.state == ShiftState.ON_LUNCH) durations.lunch else durations.activeWork
                val baseDelay = autoLunchRefreshDelayMillis(refreshed, now, nextActiveRefreshDelayMillis(elapsed))
                val reminderDelay = shiftEndRefreshDelayMillis(app.preferences.nextShiftEndTarget(), now, baseDelay)
                val delayMillis = shiftEndRefreshDelayMillis(app.notifications.nextWeeklyTarget, now, reminderDelay)
                withTimeoutOrNull(delayMillis) { wake.receive() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Refresh delayed; persisted session is unchanged", e)
                withTimeoutOrNull(60_000L) { wake.receive() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        wake.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile var isRunning = false
            private set
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
