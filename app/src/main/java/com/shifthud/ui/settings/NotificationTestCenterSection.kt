package com.shifthud.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.*
import kotlinx.coroutines.launch

@Composable internal fun NotificationTestCenterSection() {
    val context = LocalContext.current
    val app = context.applicationContext as ShiftHudApplication
    val center = app.notifications.testCenter
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var resumeRevision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; resumeRevision++; onPauseOrDispose { } }
    com.shifthud.ui.components.ExpandableSection("Notification Diagnostics","Test alerts, Garmin forwarding, and Android notification channels.") {
    Text("Tests post real Android alerts. Shifts, reminders and snoozes stay unchanged. Check Garmin separately; watch delivery cannot be confirmed.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    TestNotificationChannel.entries.forEach { channel ->
        var result by remember(channel, resumeRevision) { mutableStateOf<NotificationTestResult?>(null) }
        var details by rememberSaveable(channel) { mutableStateOf(false) }
        val status = remember(revision, channel) { center.status(channel) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick={details=!details},modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics {
                contentDescription="${channel.label} details"
                stateDescription=if(details) "Expanded" else "Collapsed"
            }) {
                Text(channel.label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                Text(if(details) "−" else "+",Modifier.clearAndSetSemantics {})
            }
            Button(onClick = { scope.launch {
                result = try { if (channel == TestNotificationChannel.UPCOMING) app.upcoming.test(center) else center.post(channel) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { NotificationTestResult(false, "Could not read tomorrow’s schedule. Please try again.") }
                revision++
            } },
                modifier = Modifier.semantics { contentDescription = "Test ${channel.label}" }) { Text("TEST") }
            }
            result?.let { Text(it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if(details) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Permission: ${if(status.permissionGranted) "Granted" else "Denied"}",style=MaterialTheme.typography.bodySmall)
            Text("App notifications: ${if(status.appEnabled) "Enabled" else "Disabled"}",style=MaterialTheme.typography.bodySmall)
            Text(status.channel.description, style = MaterialTheme.typography.bodySmall)
            status.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = {
                try { context.startActivity(center.settingsIntent(channel)) }
                catch (_: android.content.ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }) { Text("ANDROID NOTIFICATION SETTINGS") }
            }
            HorizontalDivider()
        }
    }
    Text("Work milestones — unavailable (not implemented)", style = MaterialTheme.typography.bodySmall)
    Text("Wait at least 3 seconds between tests. Android alert limits, sound, vibration, and Do Not Disturb still apply.", style = MaterialTheme.typography.bodySmall)
    }
}
