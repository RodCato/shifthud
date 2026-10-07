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
    val calendar by vm.calendar.collectAsState()
    LaunchedEffect(Unit) { vm.calendarMonth(YearMonth.now()) }
    var showWeek by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }
    var showPrevious by remember { mutableStateOf(false) }
    var recordId by remember { mutableStateOf<Long?>(null) }
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
        if (data.session != null || data.pay.sessions.any { it.manuallyEntered }) {
            OutlinedButton(onClick = { showRecords = true }, enabled = !busy) { Text("TIME RECORD / EDIT TIME") }
        }
        OutlinedButton(onClick = { showPrevious = true }, enabled = !busy) { Text("ADD PREVIOUS SHIFT") }
        if (showRecords) AlertDialog(onDismissRequest = { showRecords = false }, title = { Text("Time records") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ((calendar.sessions + data.pay.sessions).filter { it.manuallyEntered } + listOfNotNull(data.session)).distinctBy { it.id }
                    .sortedByDescending { it.clockIn }.forEach { record ->
                        val start = record.clockIn.atZone(zone)
                        TextButton(onClick = { recordId = record.id; showRecords = false }) {
                            Text("${start.toLocalDate().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))} · ${start.toLocalTime().format(timeFormat)}" + if (record.manuallyEntered) " · Manually added" else "")
                        }
                    }
            }
        }, confirmButton = { TextButton(onClick = { showRecords = false }) { Text("Close") } })
        recordId?.let { id ->
            val record by remember(id) { vm.record(id) }.collectAsState(initial = null)
            record?.let { TimeRecordDialog(it, vm, busy, data.now) { recordId = null } }
        }
        if (showPrevious) PreviousShiftDialog(data, vm, busy, dismiss = { showPrevious = false }, openRecord = {
            recordId = it; showPrevious = false
        })
        HorizontalDivider()
        val week = estimator.weekBreakdown(data.pay.sessions, data.pay.rates, data.now, zone)
        TextButton(enabled = data.pay.loaded, onClick = { showWeek = true }) { Text(WORK_WEEK_LABEL, style = MaterialTheme.typography.titleMedium) }
        if (data.pay.loaded) {
            Text("Paid: ${com.shifthud.domain.calendar.recordDuration(week.paid)}")
            Text("Est. gross: ${week.grossCents?.let { money(it, locale) } ?: "Unavailable"}", style = MaterialTheme.typography.titleLarge)
        } else Text(data.pay.error ?: "Loading weekly records…")
        TextButton(enabled = data.pay.loaded, onClick = { showWeek = true }) { Text("VIEW WEEK BREAKDOWN") }
        WorkCalendarSection(data, calendar, vm, busy, week, showWeek && data.pay.loaded, { showWeek = false }) { recordId = it }
        Text("Personal estimates only. Base pay excludes overtime premiums, taxes, withholding, bonuses, differentials, and payroll rounding. Not an official Publix app or employer timekeeping record.", style = MaterialTheme.typography.bodySmall)
    }
}
