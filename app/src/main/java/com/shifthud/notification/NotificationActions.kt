package com.shifthud.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shifthud.ShiftHudApplication
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.widget.*
import kotlinx.coroutines.*
import java.time.LocalDate

/** Reuse the existing transaction/action executor, including stale-session checks and reconciliation. */
class NotificationActionExecutor(repository: ShiftRepository, engine: ShiftEngine, refresh: suspend () -> Unit) {
    private val actions = WidgetActionExecutor(repository, engine, refresh)
    suspend fun execute(action: LunchAction, sessionId: Long): Boolean = actions.execute(
        WidgetCommand(if (action == LunchAction.START_LUNCH) WidgetOperation.START_LUNCH else WidgetOperation.END_LUNCH, sessionId, LocalDate.now()))
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val snooze = intent.action == ShiftNotifications.SNOOZE_ACTION
        val action = runCatching { LunchAction.valueOf(intent.action.orEmpty()) }.getOrNull()
        if (!snooze && action == null) return
        val receipt = intent.getStringExtra("receipt")
        if (snooze && receipt == null) return
        val sessionId = intent.getLongExtra("sessionId", -1).takeIf { it > 0 } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val app = context.applicationContext as ShiftHudApplication
                    if (snooze) {
                        try { app.notifications.snooze(sessionId, receipt!!) }
                        finally { app.widgetRefresh.refresh() }
                    } else NotificationActionExecutor(app.repository, app.engine) { app.widgetRefresh.refresh() }.execute(action!!, sessionId)
                }
            } catch (e: Exception) { Log.w("ShiftHUDAction", "Action reconciliation deferred", e) }
            finally { pending.finish() }
        }
    }
}
