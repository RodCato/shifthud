package com.shifthud.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.weekly.*
import com.shifthud.ui.ShiftViewModel

@Composable internal fun WeeklyTargetSettingsSection(vm: ShiftViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as ShiftHudApplication
    val settings by app.preferences.weeklyTargetSettings.collectAsState(initial = WeeklyTargetSettings())
    var hours by rememberSaveable(settings.targetMinutes) { mutableStateOf(java.math.BigDecimal(settings.targetMinutes).divide(java.math.BigDecimal(60), 4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(app.notifications.weeklyStatus()) }
    LifecycleResumeEffect(Unit) { status = app.notifications.weeklyStatus(); onPauseOrDispose { } }
    Text("Weekly paid-hours target", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Weekly target tracking", Modifier.weight(1f))
        Switch(settings.enabled, { vm.weeklyTarget(settings.copy(enabled = it)) }, enabled = enabled)
    }
    OutlinedTextField(hours, { hours = it; error = null }, label = { Text("Target (hours)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = enabled)
    Button(enabled = enabled, onClick = {
        val minutes = runCatching { parseWeeklyTargetMinutes(hours) }.getOrNull()
        if (minutes == null) error = "Enter more than 0 and at most 168 hours (for example 40 or 37.5)."
        else { vm.weeklyTarget(settings.copy(targetMinutes = minutes)); error = null }
    }) { Text("Save weekly target") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("Saturday–Friday. Actual paid work excludes lunch. Never clocks you out automatically.")
    Text("Warning thresholds", style = MaterialTheme.typography.titleMedium)
    WEEKLY_WARNING_MINUTES.sortedDescending().forEach { minutes ->
        Row(Modifier.fillMaxWidth().toggleable(minutes in settings.warnings, enabled = enabled, role = Role.Checkbox,
            onValueChange = { checked -> vm.weeklyTarget(settings.copy(warnings = if (checked) settings.warnings + minutes else settings.warnings - minutes)) }),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(minutes in settings.warnings, onCheckedChange = null)
            Text("$minutes minutes remaining")
        }
    }
    Text("Tracking also alerts at the target. Passed boundaries are skipped after setup or target changes. Warnings at or above the target are skipped.", style = MaterialTheme.typography.bodySmall)
    Text("Weekly hours reminder status", style = MaterialTheme.typography.titleMedium)
    Text(status.description)
    Text("Android controls sound, vibration, and Do Not Disturb. Standard notifications can mirror to your watch; no watch actions are required.")
    OutlinedButton(onClick = {
        try { context.startActivity(app.notifications.weeklySettingsIntent()) }
        catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
    }) { Text("OPEN WEEKLY HOURS NOTIFICATION SETTINGS") }
}
