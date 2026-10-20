package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FrozenWorkflowControls(tasks:List<FrozenTask>, selected:String,busy:Boolean,
    start:(LocalDate)->Unit,select:(String)->Unit,import:()->Unit) {
    var choosing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val today=remember { LocalDate.now() }
    TextButton(enabled=!busy,onClick={choosing=true}) { Text("Start Tuesday Frozen Workflow",Modifier.fillMaxWidth()) }
    Text("Start only when assigned to Frozen. Select an assignment date; existing work is preserved.",style=MaterialTheme.typography.bodySmall)
    if(selected.isNotBlank()) TextButton(enabled=!busy,onClick=import) { Text("Import Endcap Sheet",Modifier.fillMaxWidth()) }
    TextButton(onClick={history=true}) { Text("Choose saved workflow / Monday history",Modifier.fillMaxWidth()) }
    if(choosing) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={choosing=false},confirmButton={TextButton(enabled=picker.selectedDateMillis!=null,onClick={
            start(Instant.ofEpochMilli(picker.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate());choosing=false
        }) { Text("Start / reopen") }},dismissButton={TextButton(onClick={choosing=false}) { Text("Cancel") }}) {
            DatePicker(picker,title={Text("Frozen assignment date")})
        }
    }
    if(history) AlertDialog(onDismissRequest={history=false},title={Text("Saved workflows")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        TextButton(onClick={select("");history=false}) { Text("Monday · $FROZEN_MONDAY") }
        tasks.flatMap { it.workflows }.distinct().sortedDescending().forEach { date ->
            TextButton(onClick={select(date);history=false}) { Text("Tuesday workflow · $date") }
        }
    }},confirmButton={TextButton(onClick={history=false}) { Text("Close") }})
}
