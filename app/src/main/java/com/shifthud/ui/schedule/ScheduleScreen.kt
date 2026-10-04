package com.shifthud.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.shifthud.domain.model.*
import com.shifthud.ui.*
import java.time.*

@Composable fun ScheduleScreen(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean) {
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val today = data.now.atZone(ZoneId.systemDefault()).toLocalDate()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Schedule", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { editingId = 0 }, enabled = data.loaded && !busy) { Text("ADD SHIFT") }
        if (!data.loaded) CircularProgressIndicator()
        else if (data.schedule.none { it.date >= today }) Text("No upcoming shifts. Enter your schedule manually.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data.schedule.filter { it.date >= today }, key = { it.id }) { shift ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (shift.date == today) "TODAY • ${shift.date.format(dateFormat)}" else shift.date.format(dateFormat), style = MaterialTheme.typography.titleMedium)
                        Text(shift.timeLabel())
                        shift.plannedLunchMinutes?.let { Text("Planned lunch: $it minutes") }
                        shift.notes?.let { Text(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editingId = shift.id }, enabled = !busy) { Text("Edit") }
                            TextButton(onClick = { deletingId = shift.id }, enabled = !busy) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    editingId?.let { id ->
        val existing = data.schedule.firstOrNull { it.id == id }
        key(id) { ShiftEditor(existing, today, busy, { editingId = null }) { vm.save(it) { editingId = null } } }
    }
    deletingId?.let { id -> AlertDialog(onDismissRequest = { if (!busy) deletingId = null }, title = { Text("Delete scheduled shift?") }, text = { Text("Any recorded work session will be kept, without its schedule association.") }, confirmButton = { TextButton(onClick = { vm.delete(id) { deletingId = null } }, enabled = !busy) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletingId = null }, enabled = !busy) { Text("Cancel") } }) }
}

@Composable private fun ShiftEditor(existing: ScheduledShift?, today: LocalDate, busy: Boolean, dismiss: () -> Unit, save: (ScheduledShift) -> Unit) {
    val valuesSaver = listSaver<SchedulePickerValues, Long>(
        save = { listOf(it.date.toEpochDay(), it.start.toNanoOfDay(), it.end.toNanoOfDay()) },
        restore = { SchedulePickerValues(LocalDate.ofEpochDay(it[0]), LocalTime.ofNanoOfDay(it[1]), LocalTime.ofNanoOfDay(it[2])) },
    )
    var values by rememberSaveable(stateSaver = valuesSaver) { mutableStateOf(SchedulePickerValues.initial(existing, today)) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    var lunch by rememberSaveable { mutableStateOf(existing?.plannedLunchMinutes?.toString() ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (existing == null) "Add shift" else "Edit shift") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerField("Date", values.date.editorLabel(locale), !busy) { picker = "date" }
            PickerField("Start", values.start.editorLabel(locale), !busy) { picker = "start" }
            PickerField("End", values.end.editorLabel(locale), !busy) { picker = "end" }
            Text("An end at or before start means the next day.")
            OutlinedTextField(lunch, { lunch = it }, label = { Text("Lunch minutes (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(notes, { notes = it }, label = { Text("Note (optional)") }, maxLines = 3)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            val shift = try {
                val planned = if (lunch.isBlank()) null else lunch.toInt().also { require(it in 0..1440) }
                ScheduledShift(existing?.id ?: 0, values.date, values.start, values.end, planned, notes.trim().ifBlank { null })
            } catch (_: Exception) { error = "Enter lunch of 0–1440 minutes, or leave it blank."; null }
            if (shift != null) save(shift)
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
    when (picker) {
        "date" -> ScheduleDatePicker(values.date, { picker = null }) {
            values = values.copy(date = it)
            picker = null
        }
        "start", "end" -> {
            val editingStart = picker == "start"
            ScheduleTimePicker(if (editingStart) "Start time" else "End time", if (editingStart) values.start else values.end, { picker = null }) {
                values = if (editingStart) values.copy(start = it) else values.copy(end = it)
                picker = null
            }
        }
    }
}
