package com.shifthud.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.planner.*
import com.shifthud.ui.ShiftViewModel

@Composable internal fun PlannerSettingsSection(vm: ShiftViewModel, enabled: Boolean) {
    val app = LocalContext.current.applicationContext as ShiftHudApplication
    val settings by app.preferences.weeklyPlannerSettings.collectAsState(initial = PlannerSettings())
    var planned by remember(settings.plannedMinutes) { mutableStateOf(settings.plannedMinutes.coerceAtLeast(31).toString()) }
    var minimum by remember(settings.minimumMinutes) { mutableStateOf(settings.minimumMinutes.toString()) }
    var alerts by remember(settings) { mutableStateOf(settings.alerts) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Weekly Hours Planner",style=MaterialTheme.typography.titleLarge)
    LunchPlanControl(vm,!enabled)
    Text("Weekly plans affect projections only. Actual punches and lunch timers are unchanged.")
    OutlinedTextField(planned,{planned=it},label={Text("Default planned lunch duration")},singleLine=true,enabled=enabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    Text("Weeks without a saved plan inherit this default. Saved weekly plans remain independent.")
    OutlinedTextField(minimum,{minimum=it},label={Text("Minimum permitted lunch (minutes)")},singleLine=true,enabled=enabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    Text("Confirm your employer's minimum. Weekly planning permits at least 31 minutes. Legacy per-shift overrides are retained for reference but no longer drive weekly projections.",style=MaterialTheme.typography.bodySmall)
    Text("Optional final-day clock-out alerts",style=MaterialTheme.typography.titleMedium)
    listOf(60,30,15,0).forEach { offset ->
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(offset in alerts,{ checked -> alerts=if(checked) alerts+offset else alerts-offset },enabled=enabled)
            Text(if(offset==0) "Weekly target reached" else "$offset minutes before estimated departure")
        }
    }
    Text("Uses the weekly hours channel. Saved schedules must support the estimate. No alert guarantees permission to leave or delivery to Garmin.",style=MaterialTheme.typography.bodySmall)
    Button(enabled=enabled,onClick={
        val parsed=runCatching { settings.copy(plannedMinutes=planned.toInt().also { require(it in maxOf(31,minimum.toInt())..240) },minimumMinutes=minimum.toInt(),alerts=alerts) }
        parsed.onSuccess { vm.planner(it);error=null }.onFailure { error="Enter whole minutes: minimum 1–240; planned lunch must be at least 31, meet the minimum, and be at most 240." }
    }) { Text("SAVE PLANNING PREFERENCES") }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
}
