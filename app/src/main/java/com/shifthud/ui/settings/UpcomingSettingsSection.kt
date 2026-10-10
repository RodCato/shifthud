package com.shifthud.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.TestNotificationChannel
import com.shifthud.ui.schedule.*
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

@Composable internal fun UpcomingSettingsSection() {
    val context = LocalContext.current
    val app = context.applicationContext as ShiftHudApplication
    val reminders = app.upcoming
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(reminders.store.settings()) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    fun save(next: com.shifthud.notification.upcoming.UpcomingSettings) {
        busy = true
        scope.launch {
            try { reminders.configure(next); settings = next; error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Could not save reminder settings. Please try again." }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tomorrow’s shift reminder", style = MaterialTheme.typography.titleLarge)
        Row { Checkbox(settings.enabled, { save(settings.copy(enabled = it)) }, enabled = !busy); Text("Remind me of tomorrow’s schedule") }
        PickerField("Reminder time", settings.time.format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a")), !busy) { picking = true }
        Row { Checkbox(settings.daysOff, { save(settings.copy(daysOff = it)) }, enabled = !busy); Text("Notify on days off") }
        Text("Uses your manually entered schedule. Android battery management, Doze, and device restrictions may delay delivery, including while off duty.", style = MaterialTheme.typography.bodySmall)
        val status = remember(revision) { app.notifications.testCenter.status(TestNotificationChannel.UPCOMING) }
        Text(status.channel.description, style = MaterialTheme.typography.bodySmall)
        status.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { context.startActivity(app.notifications.testCenter.settingsIntent(TestNotificationChannel.UPCOMING)) }) { Text("UPCOMING SHIFT NOTIFICATION SETTINGS") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
    }
    if (picking) ScheduleTimePicker("Reminder time", settings.time, { picking = false }) { picking = false; save(settings.copy(time = it)) }
}
