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
import java.time.LocalDate

@Composable internal fun PlannerSettingsSection(vm: ShiftViewModel, enabled: Boolean) {
    val app = LocalContext.current.applicationContext as ShiftHudApplication
    val settings by app.preferences.plannerSettings.collectAsState(initial = PlannerSettings())
    val shifts by app.repository.schedule.collectAsState(initial = emptyList())
    var planned by remember(settings) { mutableStateOf(settings.plannedMinutes.toString()) }
    var minimum by remember(settings) { mutableStateOf(settings.minimumMinutes.toString()) }
    var strategy by remember(settings) { mutableStateOf(settings.strategy) }
    var custom by remember(settings) { mutableStateOf(settings.custom.mapValues { it.value.toString() }) }
    var alerts by remember(settings) { mutableStateOf(settings.alerts) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Clock-out planning",style=MaterialTheme.typography.titleLarge)
    Text("Uses the weekly target above. Planning never changes actual lunch timers, reminders or schedules.")
    OutlinedTextField(planned,{planned=it},label={Text("Planned lunch (minutes)")},singleLine=true,enabled=enabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    OutlinedTextField(minimum,{minimum=it},label={Text("Minimum permitted lunch (minutes)")},singleLine=true,enabled=enabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    Text("Confirm the minimum with your employer. Default 30 minutes is a planning assumption, not a policy determination.",style=MaterialTheme.typography.bodySmall)
    LunchStrategy.entries.forEach { choice ->
        TextButton(enabled=enabled,onClick={strategy=choice}) { Text((if(strategy==choice) "✓ " else "")+when(choice) {
            LunchStrategy.FULL -> "Full lunches, scheduled departure"
            LunchStrategy.SHORTER -> "Shorter lunches, earlier final-day departure"
            LunchStrategy.CUSTOM -> "Custom lunches by scheduled day"
        }) }
    }
    if(strategy==LunchStrategy.CUSTOM) {
        Text("Each value applies to every shift starting on that date. Blank uses the saved shift lunch or planned default.")
        shifts.map { it.date }.filter { it >= LocalDate.now().minusDays(6) }.distinct().sorted().forEach { date ->
            OutlinedTextField(custom[date]?:"",{custom=custom+(date to it)},label={Text("$date · lunch minutes")},singleLine=true,enabled=enabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        }
    }
    Text("Optional final-day clock-out alerts",style=MaterialTheme.typography.titleMedium)
    listOf(60,30,15,0).forEach { offset ->
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(offset in alerts,{ checked -> alerts=if(checked) alerts+offset else alerts-offset },enabled=enabled)
            Text(if(offset==0) "Weekly target reached" else "$offset minutes before estimated departure")
        }
    }
    Text("Uses the weekly hours channel. Saved schedules must support the estimate. No alert guarantees permission to leave or delivery to Garmin.",style=MaterialTheme.typography.bodySmall)
    Button(enabled=enabled,onClick={
        val parsed=runCatching { PlannerSettings(planned.toInt(),minimum.toInt(),strategy,custom.filterValues { it.isNotBlank() }.mapValues { it.value.toInt() },alerts) }
        parsed.onSuccess { vm.planner(it);error=null }.onFailure { error="Enter whole minutes: minimum 1–240; planned and custom lunches must be at least the minimum and at most 240." }
    }) { Text("SAVE PLANNING PREFERENCES") }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
}
