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
    start:(LocalDate)->Unit,select:(String)->Unit,startWednesday:(LocalDate)->Unit={},import:()->Unit) {
    var choosing by remember { mutableStateOf(false) }
    var wednesday by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val today=remember { LocalDate.now() }
    TextButton(enabled=!busy,onClick={wednesday=false;choosing=true}) { Text("Start Tuesday Frozen Workflow",Modifier.fillMaxWidth()) }
    TextButton(enabled=!busy,onClick={wednesday=true;choosing=true}) { Text("Start Wednesday Frozen Workflow",Modifier.fillMaxWidth()) }
    Text("Start only when assigned to Frozen. Select an assignment date; existing work is preserved.",style=MaterialTheme.typography.bodySmall)
    if(selected.isNotBlank() && !isWednesdayWorkflow(selected)) TextButton(enabled=!busy,onClick=import) { Text("Import Endcap Sheet",Modifier.fillMaxWidth()) }
    TextButton(onClick={history=true}) { Text("Choose saved workflow / Monday history",Modifier.fillMaxWidth()) }
    if(choosing) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={choosing=false},confirmButton={TextButton(enabled=picker.selectedDateMillis!=null,onClick={
            val date=Instant.ofEpochMilli(picker.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
            if(wednesday)startWednesday(date) else start(date);choosing=false
        }) { Text("Start / reopen") }},dismissButton={TextButton(onClick={choosing=false}) { Text("Cancel") }}) {
            DatePicker(picker,title={Text(if(wednesday) "Wednesday workflow assignment date" else "Frozen assignment date")})
        }
    }
    if(history) AlertDialog(onDismissRequest={history=false},title={Text("Saved workflows")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        TextButton(onClick={select("");history=false}) { Text("Monday · $FROZEN_MONDAY") }
        tasks.flatMap { it.workflows }.distinct().sortedByDescending { frozenWorkflowDate(it) }.forEach { date ->
            TextButton(onClick={select(date);history=false}) { Text("${frozenWorkflowLabel(date)} · ${frozenWorkflowDate(date)}") }
        }
    }},confirmButton={TextButton(onClick={history=false}) { Text("Close") }})
}
