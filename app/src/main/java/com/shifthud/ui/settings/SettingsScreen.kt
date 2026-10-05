package com.shifthud.ui.settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.shifthud.notification.WarningSettings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.Intent
import android.provider.Settings
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.WARNING_CHOICES
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shifthud.ui.*

@Composable fun SettingsScreen(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean) {
    val context = LocalContext.current
    val notifications = (context.applicationContext as ShiftHudApplication).notifications
    var reminderStatus by remember { mutableStateOf(notifications.reminderStatus()) }
    LifecycleResumeEffect(Unit) {
        reminderStatus = notifications.reminderStatus()
        onPauseOrDispose { }
    }
    val snoozeMinutes by (context.applicationContext as ShiftHudApplication).preferences.snoozeMinutes.collectAsState(initial = 10)
    var input by rememberSaveable(data.threshold) { mutableStateOf(data.threshold.toString()) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Lunch threshold counts active work time and pauses during lunch. This is your personal reminder preference.")
        OutlinedTextField(input, { input = it; message = null }, label = { Text("Lunch threshold (minutes)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Button(enabled = data.loaded && !busy, onClick = {
            val minutes = input.toIntOrNull()
            if (minutes == null || minutes !in 1..1440) message = "Enter 1–1440 minutes."
            else vm.threshold(minutes) { message = "Saved" }
        }) { Text("Save") }
        message?.let { Text(it) }
        Text("Early lunch warnings", style = MaterialTheme.typography.titleLarge)
        Text("A separate Lunch time alert appears at your threshold, even when early warnings are disabled.")
        Text("Reminders use active work time. Offsets at or above your threshold are skipped. 5m and 1m are useful for short shifts or testing.")
        Text(WarningSettings(data.threshold, data.warningOffsets).boundarySummary())
        Text("Save the threshold and choose warnings before clock-in. During a shift, newly enabled or changed boundaries already passed are skipped.")
        WARNING_CHOICES.forEach { offset ->
            Row(modifier = Modifier.fillMaxWidth().toggleable(value = offset in data.warningOffsets,
                enabled = data.loaded, role = Role.Checkbox, onValueChange = { vm.warning(offset, it) }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = offset in data.warningOffsets, enabled = data.loaded, onCheckedChange = null)
                Text("$offset ${if (offset == 1) "minute" else "minutes"} before")
            }
        }
        Text("Lunch snooze duration", style = MaterialTheme.typography.titleLarge)
        Text("Applies to future snooze actions. An existing snooze target stays unchanged.")
        com.shifthud.notification.SNOOZE_CHOICES.forEach { minutes ->
            Row(Modifier.fillMaxWidth().toggleable(value = snoozeMinutes == minutes,
                enabled = data.loaded && !busy, role = Role.RadioButton,
                onValueChange = { vm.snoozeMinutes(minutes) }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = snoozeMinutes == minutes, onClick = null)
                Text("$minutes minutes")
            }
        }
        Text("Lunch Reminder Status", style = MaterialTheme.typography.titleLarge)
        Text(reminderStatus.description)
        reminderStatus.advice?.let { Text(it) }
        Text("Android controls this channel. Your sound, vibration, priority, and Do Not Disturb choices are respected.")
        OutlinedButton(onClick = {
            val intent = notifications.reminderSettingsIntent()
            try { context.startActivity(intent) }
            catch (_: android.content.ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text("OPEN NOTIFICATION SETTINGS") }
    }
}
