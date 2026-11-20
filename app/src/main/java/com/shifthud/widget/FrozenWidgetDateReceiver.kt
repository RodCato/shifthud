package com.shifthud.widget

import android.content.*
import com.shifthud.ShiftHudApplication
import kotlinx.coroutines.*

/** Redraw only: date changes must not create shifts, reconcile lunch, or send alerts. */
class FrozenWidgetDateReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_DATE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {withTimeout(8_000) {(context.applicationContext as ShiftHudApplication).widgetRefresh.redrawHistorical()}}
            catch(e:Exception) {android.util.Log.w("ShiftHUDFrozen","Date redraw deferred to the next widget update",e)}
            finally {pending.finish()}
        }
    }
}
