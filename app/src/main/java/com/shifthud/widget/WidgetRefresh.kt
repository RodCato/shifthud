package com.shifthud.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.*
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.ShiftState
import com.shifthud.service.ActiveShiftService
import com.shifthud.service.synchronizeActiveRefresh
import com.shifthud.service.requiresActiveRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit

/** One refresh path for app writes, widget actions, and passive work. No timer counters are saved. */
class WidgetRefresh(private val context: Context) {
    private val mutex = Mutex()
    private val _now = MutableStateFlow(Instant.now())
    val now = _now.asStateFlow()
    fun markNow() { _now.value = Instant.now() }

    /** Historical writes only redraw; they must not reconcile lunch or touch alerts/services. */
    suspend fun redrawHistorical() = mutex.withLock {
        try {
            markNow()
            ShiftHudWidget().updateAll(context)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("ShiftHUDWidget", "Historical redraw deferred", e) }
    }

    suspend fun refresh(mayStartService: Boolean = true, synchronizeService: Boolean = true): Boolean = mutex.withLock {
        try {
            val app = context.applicationContext as ShiftHudApplication
            app.repository.reconcileAutoLunch()
            val state = app.repository.snapshot().session?.state
            val redraw: suspend () -> Boolean = {
                try { app.notifications.refresh() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w("ShiftHUDNotification", "Notification update deferred", e) }
                _now.value = Instant.now()
                val installed = GlanceAppWidgetManager(context).getGlanceIds(ShiftHudWidget::class.java).isNotEmpty()
                if (synchronizeService || !requiresActiveRefresh(state)) configureWork(installed)
                if (installed) ShiftHudWidget().updateAll(context)
                true
            }
            if (synchronizeService) synchronizeActiveRefresh(state, mayStartService,
                start = { ActiveShiftService.start(context) },
                stop = { ActiveShiftService.stop(context) }, refresh = redraw)
            else redraw()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // A redraw failure must never turn a successful database write into a failed UI action.
            Log.w("ShiftHUDWidget", "Widget refresh deferred to next system update", e)
            false
        }
    }

    suspend fun configureWork(installed: Boolean = true) {
        val app = context.applicationContext as ShiftHudApplication
        val active = app.repository.snapshot().session?.state.let { it == ShiftState.WORKING || it == ShiftState.ON_LUNCH }
        val work = WorkManager.getInstance(context)
        if (installed && active) {
            // Slow fallback only: foreground service drives live elapsed-minute refreshes.
            // KEEP prevents each app edit/widget instance from resetting the existing cadence.
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                    .setInitialDelay(15, TimeUnit.MINUTES).build())
        } else work.cancelUniqueWork(WORK_NAME)
    }
    companion object { const val WORK_NAME = "shifthud-active-widget-refresh" }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ShiftHudApplication
        return if (app.widgetRefresh.refresh(mayStartService = false)) Result.success() else Result.retry()
    }
}
