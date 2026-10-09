package com.shifthud.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.weekly.*
import com.shifthud.domain.model.ShiftState
import com.shifthud.domain.usecase.AutoLunchSettings
import com.shifthud.ui.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.text.NumberFormat

@Composable internal fun WeeklyTargetSection(data: ShiftUiState) {
    val context = LocalContext.current
    val app = context.applicationContext as ShiftHudApplication
    val settings by app.preferences.weeklyTargetSettings.collectAsState(initial = WeeklyTargetSettings())
    val lunch by app.preferences.autoLunchSettings.collectAsState(initial = AutoLunchSettings())
    val locale = LocalConfiguration.current.locales[0]
    if (!settings.enabled) return
    HorizontalDivider()
    if (!data.pay.loaded) { Text("Weekly target: ${data.pay.error ?: "Loading records…"}"); return }
    val zone = ZoneId.systemDefault()
    val records = (data.pay.sessions + listOfNotNull(data.session)).distinctBy { it.id }
    val result = weeklyTarget(records, data.schedule, settings, lunch.minutes, data.now, zone)
    Text(result.status, style = MaterialTheme.typography.titleMedium)
    Text("${result.target.display()} target")
    Text("Worked: ${result.paid.display()}")
    Text("Remaining: ${result.remaining.display()}")
    Text("Progress: ${NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(result.progress)}")
    LinearProgressIndicator(progress = { result.progress.coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
    if (result.active?.state == ShiftState.ON_LUNCH) Text("Remaining paid work: ${result.remaining.display()} · Paused during lunch")
    result.projectedClockOut?.let { projected ->
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val format = DateTimeFormatter.ofPattern(if (projected.atZone(zone).toLocalDate() == data.now.atZone(zone).toLocalDate()) pattern else "EEE, MMM d · $pattern", locale)
        Text(if (result.lunchEstimate) "Projected clock-out after lunch (estimate): ${projected.atZone(zone).format(format)}"
            else "Projected ${result.target.display()} clock-out: ${projected.atZone(zone).format(format)}")
        Text(if (result.lunchEstimate) "Assumes lunch resumes at its automatic end, with no additional unpaid breaks." else "Assumes no additional unpaid breaks.", style = MaterialTheme.typography.bodySmall)
    }
    if (result.active?.state == ShiftState.ON_LUNCH && result.projectedClockOut == null && !result.remaining.isZero)
        Text("Clock-out projection unavailable until lunch ends; its end time is unknown.", style = MaterialTheme.typography.bodySmall)
    Text("Projected weekly total: ${result.projected.display()}")
    when {
        !result.overage.isZero -> Text("Projected overage: ${result.overage.display()}", color = MaterialTheme.colorScheme.error)
        !result.shortfall.isZero -> Text("Projected shortfall: ${result.shortfall.display()}")
        else -> Text("Projected to meet target")
    }
    Text("Personal projection, not an official payroll forecast. Remaining schedules assume their planned lunch, or the configured ${lunch.minutes}-minute default. Actual punches remain authoritative.", style = MaterialTheme.typography.bodySmall)
}
