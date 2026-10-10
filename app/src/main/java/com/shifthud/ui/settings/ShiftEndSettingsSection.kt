package com.shifthud.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.*
import com.shifthud.ui.ShiftViewModel

@Composable internal fun ShiftEndSettingsSection(vm: ShiftViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as ShiftHudApplication
    val settings by app.preferences.shiftEndSettings.collectAsState(initial = ShiftEndSettings())
    var status by remember { mutableStateOf(app.notifications.shiftEndStatus()) }
    LifecycleResumeEffect(Unit) {
        status = app.notifications.shiftEndStatus()
        onPauseOrDispose { }
    }
    Text("Shift-end reminder", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Enabled", Modifier.weight(1f))
        Switch(settings.enabled, { vm.shiftEnd(settings.copy(enabled = it)) }, enabled = enabled)
    }
    Text("Personal reminder based on scheduled out. Never clocks you out automatically. Unscheduled shifts have no end reminder.")
    Text("Remind me")
    (SHIFT_END_LEADS + if (app.preferences.debugSettings) listOf(2) else emptyList()).forEach { minutes ->
        Row(Modifier.fillMaxWidth().toggleable(settings.leadMinutes == minutes, enabled = enabled,
            role = Role.RadioButton, onValueChange = { vm.shiftEnd(settings.copy(leadMinutes = minutes)) }),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(settings.leadMinutes == minutes, onClick = null)
            Text("$minutes minutes before" + if (minutes == 2) " (debug test)" else "")
        }
    }
    Text("Shift-end snooze duration", style = MaterialTheme.typography.titleMedium)
    Text("Repeatable wall-clock snooze, including past scheduled out. Changes apply to the next snooze.")
    SHIFT_END_SNOOZES.forEach { minutes ->
        Row(Modifier.fillMaxWidth().toggleable(settings.snoozeMinutes == minutes, enabled = enabled,
            role = Role.RadioButton, onValueChange = { vm.shiftEnd(settings.copy(snoozeMinutes = minutes)) }),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(settings.snoozeMinutes == minutes, onClick = null)
            Text("$minutes minutes")
        }
    }
    Text("Shift End Reminder Status", style = MaterialTheme.typography.titleMedium)
    Text(status.description)
    status.advice?.let { Text(it.replace("Lunch reminders", "Shift end reminders")) }
    Text("Android controls sound, vibration, priority, and Do Not Disturb. Choose a sound independently of Lunch reminders.")
    OutlinedButton(onClick = {
        try { context.startActivity(app.notifications.shiftEndSettingsIntent()) }
        catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }) { Text("OPEN SHIFT END NOTIFICATION SETTINGS") }
}
