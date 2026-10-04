package com.shifthud.ui.settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shifthud.ui.*

@Composable fun SettingsScreen(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean) {
    var input by rememberSaveable(data.threshold) { mutableStateOf(data.threshold.toString()) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Lunch threshold counts active work time and pauses during lunch. This is your personal reminder preference.")
        OutlinedTextField(input, { input = it; message = null }, label = { Text("Lunch threshold (minutes)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Button(enabled = data.loaded && !busy, onClick = {
            val minutes = input.toIntOrNull()
            if (minutes == null || minutes !in 1..1440) message = "Enter 1–1440 minutes."
            else vm.threshold(minutes) { message = "Saved" }
        }) { Text("Save") }
        message?.let { Text(it) }
    }
}
