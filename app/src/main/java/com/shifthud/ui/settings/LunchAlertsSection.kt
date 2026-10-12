package com.shifthud.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.*
import kotlinx.coroutines.launch

@Composable internal fun LunchAlertsSection() {
    val context=LocalContext.current
    val app=context.applicationContext as ShiftHudApplication
    val settings by app.lunchAlerts.settings.collectAsState()
    val scope=rememberCoroutineScope()
    var input by rememberSaveable(settings.snoozeMinutes){mutableStateOf(settings.snoozeMinutes.toString())}
    var message by remember { mutableStateOf<String?>(null) }
    fun save(value:com.shifthud.notification.lunch.LunchAlertSettings) { scope.launch {
        try { app.lunchAlerts.save(value);message="Saved" }
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){message="Could not save lunch alerts. Try again."}
    } }
    com.shifthud.ui.components.ExpandableSection("Lunch Alerts","During lunch · minimum, planned and snooze") {
        Text("These alerts never end lunch or change punches. Existing automatic lunch-end settings remain separate.")
        AlertToggle("Minimum lunch alert enabled",settings.minimum){save(settings.copy(minimum=it))}
        AlertToggle("Planned lunch alert enabled",settings.planned){save(settings.copy(planned=it))}
        Text("Planned duration uses the workweek in which the shift began. Minimum and planned alerts combine when both are 31 minutes.")
        Text("Default snooze duration: ${settings.snoozeMinutes} minutes")
        listOf(5,10,15,20,30).forEach { minutes -> TextButton(onClick={save(settings.copy(snoozeMinutes=minutes))}) { Text("Snooze $minutes minutes") } }
        OutlinedTextField(input,{input=it},label={Text("Custom snooze minutes (1–120)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        Button(onClick={save(settings.copy(snoozeMinutes=input.toInt()))},enabled=input.toIntOrNull() in 1..120) { Text("SAVE SNOOZE DURATION") }
        AlertToggle("Repeat snooze enabled",settings.repeat){save(settings.copy(repeat=it))}
        Text("When enabled, reminders repeat at the default interval after a snooze expires, until lunch ends. Snoozing again replaces the pending reminder. Otherwise each reminder waits for another Snooze action.")
        val center=app.notifications.testCenter
        listOf(TestNotificationChannel.MINIMUM_LUNCH,TestNotificationChannel.PLANNED_LUNCH,TestNotificationChannel.LUNCH_SNOOZE).forEach { channel ->
            Button(onClick={message=center.post(channel).message}) { Text("Test ${channel.label.lowercase()}") }
        }
        message?.let { Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Polite}) }
        OutlinedButton(onClick={context.startActivity(center.settingsIntent(TestNotificationChannel.MINIMUM_LUNCH))}) { Text("ANDROID LUNCH NOTIFICATION SETTINGS") }
        Text("Android permission, sound, vibration, Do Not Disturb and battery restrictions apply. Tests do not verify Garmin delivery or change reminder state.")
    }
}

@Composable private fun AlertToggle(label:String,value:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Checkbox,onValueChange=change)) {
        Checkbox(value,onCheckedChange=null)
        Text(label,Modifier.weight(1f))
    }
}
