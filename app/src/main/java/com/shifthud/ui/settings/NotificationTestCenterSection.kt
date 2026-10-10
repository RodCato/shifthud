package com.shifthud.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.*

@Composable internal fun NotificationTestCenterSection() {
    val context = LocalContext.current
    val center = (context.applicationContext as ShiftHudApplication).notifications.testCenter
    var revision by remember { mutableIntStateOf(0) }
    var resumeRevision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; resumeRevision++; onPauseOrDispose { } }
    Text("Notification Test Center", style = MaterialTheme.typography.titleLarge)
    Text("Send real Android notifications to check Garmin Connect forwarding. ShiftHUD cannot confirm watch delivery. Tests do not change shifts, reminders, or snoozes.")
    TestNotificationChannel.entries.forEach { channel ->
        var result by remember(channel, resumeRevision) { mutableStateOf<NotificationTestResult?>(null) }
        val status = remember(revision, channel) { center.status(channel) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(channel.label, style = MaterialTheme.typography.titleMedium)
            Text(status.channel.description, style = MaterialTheme.typography.bodySmall)
            status.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { result = center.post(channel); revision++ },
                modifier = Modifier.semantics { contentDescription = "Test ${channel.label}" }) { Text("TEST") }
            result?.let { Text(it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            OutlinedButton(onClick = {
                try { context.startActivity(center.settingsIntent(channel)) }
                catch (_: android.content.ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }) { Text("ANDROID NOTIFICATION SETTINGS") }
            HorizontalDivider()
        }
    }
    Text("Work milestones — unavailable (not implemented)", style = MaterialTheme.typography.bodySmall)
    Text("Wait at least 3 seconds between tests. Android alert limits, sound, vibration, and Do Not Disturb still apply.", style = MaterialTheme.typography.bodySmall)
}
