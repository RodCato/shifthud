package com.shifthud.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.*
import com.shifthud.ui.*
import com.shifthud.ui.schedule.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun PreviousShiftDialog(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean, dismiss: () -> Unit, openRecord: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val timeFormat = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    var day by rememberSaveable { mutableLongStateOf(data.now.atZone(zone).toLocalDate().minusDays(1).toEpochDay()) }
    var start by rememberSaveable { mutableStateOf("04:00") }
    var end by rememberSaveable { mutableStateOf("13:00") }
    var lunchStart by rememberSaveable { mutableStateOf<String?>(null) }
    var lunchEnd by rememberSaveable { mutableStateOf<String?>(null) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf<TimeEvent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlap by remember { mutableStateOf<Long?>(null) }
    val input = HistoricalShiftInput(LocalDate.ofEpochDay(day), LocalTime.parse(start), LocalTime.parse(end), lunchStart?.let(LocalTime::parse), lunchEnd?.let(LocalTime::parse))
    val preview = runCatching { input.session(zone, data.now) }
    fun value(event: TimeEvent): LocalTime? = when (event) {
        TimeEvent.CLOCK_IN -> input.clockIn; TimeEvent.CLOCK_OUT -> input.clockOut
        TimeEvent.LUNCH_START -> input.lunchStart; TimeEvent.LUNCH_END -> input.lunchEnd
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Add previous shift") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Create a completed personal record from your actual punches. Your employer timecard remains authoritative.", style = MaterialTheme.typography.bodySmall)
            PickerField("Date · shift start", input.date.format(dateFormat), !busy) { datePicker = true }
            TimeEvent.entries.forEach { event ->
                val time = value(event)
                val optional = event == TimeEvent.LUNCH_START || event == TimeEvent.LUNCH_END
                PickerField(event.label + if (optional) " (optional)" else "", time?.let {
                    it.format(timeFormat) + if (it < input.clockIn) " · ${input.date.plusDays(1).format(dateFormat)}" else ""
                } ?: "Not recorded", !busy) { timePicker = event }
                if (optional && time != null) TextButton(enabled = !busy, onClick = {
                    if (event == TimeEvent.LUNCH_START) lunchStart = null else lunchEnd = null
                    error = null
                }) { Text("Clear ${event.label.lowercase()}") }
            }
            Text("Times earlier than clock in use the following day. Repeated daylight-saving times use the first occurrence; Edit Time preserves that offset.", style = MaterialTheme.typography.bodySmall)
            preview.getOrNull()?.let { session ->
                val durations = vm.engine.durations(session, data.threshold, data.now)
                Text("Store time: ${durations.store.display()}\nLunch: ${durations.lunch.display()}\nPaid: ${durations.paid.display()}")
                val gross = runCatching { PayEstimator(vm.engine).session(session, data.pay.rates, data.now, zone).grossCents }.getOrNull()
                Text(gross?.let { "Estimated gross: ${money(it, locale)}" } ?: "Estimated gross unavailable: no effective pay rate.")
            }
            (error ?: preview.exceptionOrNull()?.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && preview.isSuccess, onClick = {
        vm.addHistorical(input, zone) { id, failure ->
            when {
                failure is SessionOverlapException -> overlap = failure.existing.id
                failure != null -> error = failure.message
                id != null -> openRecord(id)
            }
        }
    }) { Text("Save previous shift") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancel") } })
    if (datePicker) ScheduleDatePicker(input.date, { datePicker = false }) { day = it.toEpochDay(); datePicker = false; error = null }
    timePicker?.let { event ->
        ScheduleTimePicker(event.label, value(event) ?: LocalTime.of(if (event == TimeEvent.LUNCH_START) 9 else 10, 0), { timePicker = null }) {
            when (event) {
                TimeEvent.CLOCK_IN -> start = it.toString(); TimeEvent.CLOCK_OUT -> end = it.toString()
                TimeEvent.LUNCH_START -> lunchStart = it.toString(); TimeEvent.LUNCH_END -> lunchEnd = it.toString()
            }
            timePicker = null; error = null
        }
    }
    overlap?.let { id -> AlertDialog(onDismissRequest = { overlap = null }, title = { Text("A work session already exists during this time.") },
        text = { Text("Overlapping records would double-count hours and pay. Edit the existing record or cancel and check your punches.") },
        confirmButton = { TextButton(onClick = { openRecord(id) }) { Text("EDIT EXISTING") } },
        dismissButton = { TextButton(onClick = { overlap = null }) { Text("CANCEL") } }) }
}
