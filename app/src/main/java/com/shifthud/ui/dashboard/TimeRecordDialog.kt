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
import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.*
import com.shifthud.ui.ShiftViewModel
import com.shifthud.ui.display
import com.shifthud.ui.schedule.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun TimeRecordDialog(session: WorkSession, vm: ShiftViewModel, busy: Boolean, now: Instant, dismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val timeFormat = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    var editing by remember { mutableStateOf<Pair<WorkSession, TimeEvent>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val durations = vm.engine.durations(session, 360, now)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Time record") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Personal record only. Auto means an inferred lunch end; corrections do not change employer payroll.")
            if (session.manuallyEntered) Text("Manually added previous shift")
            Text("Paid ${durations.paid.display()} · Store ${durations.store.display()} · Lunch ${durations.lunch.display()}")
            TimeEvent.entries.forEach { event ->
                val value = event.timestamp(session)
                if (value == null) Text("${event.label}: Not recorded")
                else PickerField(event.label, value.atZone(zone).let { "${it.format(dateFormat)} · ${it.format(timeFormat)}" } +
                    if (event == TimeEvent.LUNCH_END && session.lunchEndAutomatic) " · Auto" else "", !busy) { editing = session to event }
            }
            if (session.state == com.shifthud.domain.model.ShiftState.COMPLETE) TextButton(enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { confirmDelete = true }) { Text("DELETE SHIFT RECORD") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
    if (confirmDelete) DeleteShiftRecordDialog(session, vm, busy, now, { confirmDelete = false }) {
        vm.deleteHistorical(session, dismiss)
    }
    editing?.let { (originalSession, event) ->
        val original = requireNotNull(event.timestamp(originalSession))
        var date by remember(originalSession, event) { mutableStateOf(original.atZone(zone).toLocalDate()) }
        var time by remember(originalSession, event) { mutableStateOf(original.atZone(zone).toLocalTime()) }
        var datePicker by remember { mutableStateOf(false) }
        var timePicker by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) editing = null }, title = { Text("Edit ${event.label.lowercase()}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerField("Date", date.format(dateFormat), !busy) { datePicker = true }
                PickerField("Time", time.format(timeFormat), !busy) { timePicker = true }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = {
            try {
                val value = correctedInstant(date, time, zone, original)
                vm.correctTime(originalSession, event, value) { failure -> if (failure == null) editing = null else error = failure }
            } catch (e: IllegalArgumentException) { error = e.message }
        }) { Text("Save correction") } }, dismissButton = { TextButton(enabled = !busy, onClick = { editing = null }) { Text("Cancel") } })
        if (datePicker) ScheduleDatePicker(date, { datePicker = false }) { date = it; error = null; datePicker = false }
        if (timePicker) ScheduleTimePicker(event.label, time, { timePicker = false }) { time = it; error = null; timePicker = false }
    }
}
