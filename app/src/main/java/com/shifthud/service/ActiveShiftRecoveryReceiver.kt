package com.shifthud.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shifthud.ShiftHudApplication
import kotlinx.coroutines.*

/** System boot/package-replaced exemptions only; never an alarm-based service resurrection loop. */
class ActiveShiftRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val app = context.applicationContext as ShiftHudApplication
                    if (requiresActiveRefresh(app.repository.snapshot().session?.state)) ActiveShiftService.start(context)
                }
            } catch (e: Exception) { Log.w("ShiftHUDRecovery", "Recovery deferred until next app/widget interaction", e) }
            finally { pending.finish() }
        }
    }
}
