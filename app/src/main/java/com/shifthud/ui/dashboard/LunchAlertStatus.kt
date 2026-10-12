package com.shifthud.ui.dashboard

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.WorkSession
import com.shifthud.ui.countdownLabel
import java.time.*

@Composable internal fun LunchAlertStatus(session:WorkSession,now:Instant) {
    val app=LocalContext.current.applicationContext as ShiftHudApplication
    val settings by app.lunchAlerts.settings.collectAsState()
    val next by app.lunchAlerts.next.collectAsState()
    val plan by app.preferences.weeklyPlannerSettings.collectAsState(initial=null)
    val minimum=session.lunchStart!!.plusSeconds(31*60)
    Text(if(now>=minimum) "Minimum reached ✓" else if(settings.minimum) "Minimum reminder: ${Duration.between(now,minimum).countdownLabel()} remaining" else "Minimum reminder: off")
    plan?.let { Text("Planned lunch: ${it.weeklyMinutes(session.clockIn.atZone(ZoneId.systemDefault()).toLocalDate())}m${if(settings.planned) "" else " · Alert off"}") }
    next?.let { Text("Next reminder: ${Duration.between(now,it).coerceAtLeast(Duration.ZERO).countdownLabel()} remaining") }
}
