package com.shifthud.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.LocalTime

@Composable
internal fun PickerField(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            Text("Change", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDatePicker(initial: LocalDate, dismiss: () -> Unit, confirm: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = dismiss,
        confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let { confirm(dateFromPickerMillis(it)) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    ) { DatePicker(state = state, showModeToggle = false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTimePicker(title: String, initial: LocalTime, dismiss: () -> Unit, confirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    var keyboardMode by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (keyboardMode) TimeInput(state = state) else TimePicker(state = state)
                TextButton(onClick = { keyboardMode = !keyboardMode }) {
                    Text(if (keyboardMode) "Use clock" else "Use keyboard")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("Cancel") }
                    TextButton(onClick = { confirm(timeFromPicker(state.hour, state.minute)) }) { Text("OK") }
                }
            }
        }
    }
}
