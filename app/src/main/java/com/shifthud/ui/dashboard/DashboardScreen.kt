package com.shifthud.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.ui.*
import java.time.*

@Composable fun DashboardScreen(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean) {
    var showRecord by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val estimator = remember(vm.engine) { PayEstimator(vm.engine) }
    val zone = ZoneId.systemDefault()
    val today = data.now.atZone(ZoneId.systemDefault()).toLocalDate()
    val scheduled = data.schedule.firstOrNull { it.date == today }
    val session = data.session?.takeIf { it.state != ShiftState.COMPLETE || it.clockOut?.atZone(ZoneId.systemDefault())?.toLocalDate() == today }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PUBLIX SHIFT", style = MaterialTheme.typography.headlineMedium)
        Text("ShiftHUD • Personal tracker", style = MaterialTheme.typography.labelLarge)
        if (!data.loaded) { CircularProgressIndicator(); return@Column }
        if (session == null) {
            Text(if (scheduled != null) "TODAY" else "OFF TODAY", style = MaterialTheme.typography.titleLarge)
            scheduled?.let { Text(it.timeLabel(), style = MaterialTheme.typography.headlineSmall); it.notes?.let { note -> Text(note) } }
            if (scheduled == null) {
                val next = data.schedule.firstOrNull { it.date > today }
                Text(next?.let { "Next shift: ${it.date.format(dateFormat)} ${it.timeLabel()}" } ?: "No upcoming shifts. Add your schedule in Schedule.")
            }
            Button(onClick = vm::clockIn, enabled = !busy) { Text(if (scheduled == null) "CLOCK IN — UNSCHEDULED" else "CLOCK IN") }
        } else {
            val durations = vm.engine.durations(session, data.threshold, data.now)
            val gross = data.pay.rates.takeIf { it.isNotEmpty() }?.let { estimator.session(session, it, data.now, zone).grossCents }
            val linked = data.schedule.firstOrNull { it.id == session.scheduledShiftId }
            when (session.state) {
                ShiftState.WORKING -> {
                    Text("WORKING — ${durations.activeWork.display()}", style = MaterialTheme.typography.headlineSmall)
                    gross?.let { Text("Estimated gross: ${money(it, locale)}", style = MaterialTheme.typography.titleLarge) }
                    val lunch = completedLunch(session, ZoneId.systemDefault(), locale, android.text.format.DateFormat.is24HourFormat(context))
                    Text(lunch?.detail ?: if (durations.lunchRemaining.isNegative || durations.lunchRemaining.isZero) "Lunch threshold reached" else "Lunch due in ${durations.lunchRemaining.display()}")
                    if (session.lunchStart == null) Button(onClick = { vm.startLunch(session) }, enabled = !busy) { Text("START LUNCH") }
                    Button(onClick = { vm.clockOut(session) }, enabled = !busy) { Text("CLOCK OUT") }
                }
                ShiftState.ON_LUNCH -> {
                    Text("ON LUNCH — ${durations.lunch.display()}", style = MaterialTheme.typography.headlineSmall)
                    Text("Paid: ${durations.paid.display()}")
                    gross?.let { Text("Estimated gross: ${money(it, locale)}", style = MaterialTheme.typography.titleLarge) }
                    Button(onClick = { vm.endLunch(session) }, enabled = !busy) { Text("END LUNCH") }
                }
                ShiftState.COMPLETE -> {
                    Text("SHIFT COMPLETE", style = MaterialTheme.typography.headlineSmall)
                    Text("Paid: ${durations.paid.display()}")
                    gross?.let { Text("Estimated gross: ${money(it, locale)}", style = MaterialTheme.typography.titleLarge) }
                    Text("Store time: ${durations.store.display()}")
                    Text("Lunch: ${durations.lunch.display()}" + if (session.lunchEndAutomatic) " · Auto" else "")
                    scheduled?.let { Text("Today's schedule: ${it.timeLabel()}") }
                    OutlinedButton(onClick = vm::clockIn, enabled = !busy) { Text("CLOCK IN — NEW SESSION") }
                }
                ShiftState.NOT_STARTED -> Unit
            }
            if (session.state != ShiftState.COMPLETE) Text(linked?.let { "Scheduled out: ${it.scheduledEnd.format(timeFormat)}${if (it.end.toLocalDate() != it.date) " (+1 day)" else ""}" } ?: "Unscheduled shift")
        }
        data.session?.let { record ->
            OutlinedButton(onClick = { showRecord = true }, enabled = !busy) { Text("TIME RECORD / EDIT TIME") }
            if (showRecord) TimeRecordDialog(record, vm, busy, data.now) { showRecord = false }
        }
        HorizontalDivider()
        Text("THIS WEEK · Monday–Sunday", style = MaterialTheme.typography.titleMedium)
        if (data.pay.rates.isNotEmpty()) {
            val week = estimator.week(data.pay.sessions, data.pay.rates, data.now, zone)
            Text("Paid: ${week.paid.display()}")
            Text("Est. gross: ${money(week.grossCents, locale)}", style = MaterialTheme.typography.titleLarge)
        }
        data.pay.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Personal estimates only. Base pay excludes overtime premiums, taxes, withholding, bonuses, differentials, and payroll rounding. Not an official Publix app or employer timekeeping record.", style = MaterialTheme.typography.bodySmall)
    }
}
