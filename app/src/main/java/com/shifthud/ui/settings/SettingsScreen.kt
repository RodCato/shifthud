package com.shifthud.ui.settings
import androidx.compose.foundation.layout.*
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
    var warningsAllowed by remember { mutableStateOf(notifications.warningsAllowed()) }
    LifecycleResumeEffect(Unit) {
        warningsAllowed = notifications.warningsAllowed()
        onPauseOrDispose { }
    }
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
        Text("Lunch warnings", style = MaterialTheme.typography.titleLarge)
        Text("Reminders use active work time. Offsets at or above your threshold are skipped. 5m and 1m are useful for short shifts or testing.")
        WARNING_CHOICES.forEach { offset ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = offset in data.warningOffsets, enabled = data.loaded && !busy,
                    onCheckedChange = { vm.warning(offset, it) })
                Text("$offset ${if (offset == 1) "minute" else "minutes"} before")
            }
        }
        Text(if (warningsAllowed) "Lunch reminders are allowed. Sound and vibration follow your Android notification settings."
            else "Lunch warnings cannot be shown until notifications and the Lunch reminders channel are allowed. Shift and widget tracking continue.")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
            Text("Notification settings")
        }
    }
}
