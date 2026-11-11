package com.shifthud.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shifthud.ShiftHudApplication
import com.shifthud.notification.paycheck.*
import com.shifthud.ui.schedule.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable internal fun PaycheckReminderSettingsSection(provided: PaycheckReminders? = null) {
    val context=LocalContext.current
    val reminders=provided ?: (context.applicationContext as ShiftHudApplication).paycheckReminders
    val scope=rememberCoroutineScope()
    var settings by remember { mutableStateOf(reminders.store.settings()) }
    var picking by remember { mutableStateOf(false) }
    var dayMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var blocked by remember { mutableStateOf(reminders.blockedReason()) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { blocked=reminders.blockedReason() }
    LifecycleResumeEffect(Unit) { PaycheckReminders.createChannel(context);blocked=reminders.blockedReason();onPauseOrDispose{} }
    fun save(value: PaycheckReminderSettings) {
        busy=true
        scope.launch {
            try { reminders.configure(value);settings=value;error=null }
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){error="Could not save reminder. Please try again."}
            finally{busy=false}
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Import Publix Paycheck",style=MaterialTheme.typography.titleLarge)
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(settings.enabled,{save(settings.copy(enabled=it))},enabled=!busy)
            Text("Weekly paycheck import reminder")
        }
        Box {
            OutlinedButton(onClick={dayMenu=true},enabled=!busy){Text(settings.day.getDisplayName(TextStyle.FULL,Locale.getDefault()))}
            DropdownMenu(expanded=dayMenu,onDismissRequest={dayMenu=false}) {
                DayOfWeek.entries.forEach { day -> DropdownMenuItem(text={Text(day.getDisplayName(TextStyle.FULL,Locale.getDefault()))},onClick={dayMenu=false;save(settings.copy(day=day))}) }
            }
        }
        val time=settings.time.format(DateTimeFormatter.ofPattern(if(android.text.format.DateFormat.is24HourFormat(context))"H:mm" else "h:mm a"))
        PickerField("Reminder time",time,!busy){picking=true}
        Text(if(settings.enabled)"Every ${settings.day.getDisplayName(TextStyle.FULL,Locale.getDefault())} at $time · Device local time" else "Reminder disabled")
        Text("Changes save immediately. Android battery management may delay delivery. A complete saved paycheck dated on the reminder day suppresses that reminder.",style=MaterialTheme.typography.bodySmall)
        blocked?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            OutlinedButton(onClick={permission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("ALLOW NOTIFICATIONS")}
        TextButton(onClick={context.startActivity(reminders.settingsIntent())}){Text("ANDROID NOTIFICATION SETTINGS")}
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
    if(picking)ScheduleTimePicker("Paycheck reminder",settings.time,{picking=false}){picking=false;save(settings.copy(time=it))}
}
