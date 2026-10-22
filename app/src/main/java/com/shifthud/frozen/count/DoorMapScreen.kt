package com.shifthud.frozen.count

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable internal fun DoorMapScreen(data:CountRouteData,busy:Boolean,error:String?,back:()->Unit,save:(List<RouteSegment>)->Unit) {
    val conversion=remember(data.map) {simplifyDoorMap(data.map)}
    var counts by rememberSaveable {mutableStateOf(conversion.map.map {it.count?.toString().orEmpty()})}
    var validation by remember {mutableStateOf<String?>(null)}
    var confirm by remember {mutableStateOf(false)}
    fun proposed()=simpleDoorMap().mapIndexed {i,s->
        val text=counts[i].trim()
        s.copy(count=if(text.isEmpty())null else requireNotNull(text.toIntOrNull()) {"${s.label}: enter a whole number."})
    }.also(::validateMap)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick=back,enabled=!busy) {Text("Back to Count Route",Modifier.fillMaxWidth())}
        Text("Door Map Setup",Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall)
        Text("Frozen doors only. Blank = unknown; 0 = none.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        simpleDoorMap().forEachIndexed {i,segment->
            OutlinedTextField(counts[i],{value->counts=counts.toMutableList().also {it[i]=value}},enabled=!busy,
                label={Text("Aisle ${segment.aisle} — ${segment.side}",Modifier.fillMaxWidth())},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        if(conversion.warnings.isNotEmpty())Text("Some old totals need confirmation. Previous maps and progress will be kept.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        if(data.active!=null)Text("Saved counts apply to the next cycle. Current progress stays unchanged.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        (validation ?: error)?.let {Text(it,Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.error)}
        Button(onClick={try {val map=proposed();validation=null;if(conversion.warnings.isNotEmpty())confirm=true else save(map)} catch(e:IllegalArgumentException) {validation=e.message}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("SAVE DOOR COUNTS",Modifier.fillMaxWidth())}
    }
    if(confirm)AlertDialog(onDismissRequest={if(!busy)confirm=false},title={Text("Confirm Frozen door counts",Modifier.fillMaxWidth())},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        conversion.warnings.forEach {Text(it,Modifier.fillMaxWidth())}
        Text("Save the five entered counts for future cycles? Blank totals remain unknown. Original maps, active progress and history are preserved.",Modifier.fillMaxWidth())
        proposed().forEach {Text("${it.label}: ${it.count?.toString() ?: "Unknown"}",Modifier.fillMaxWidth())}
    }},confirmButton={CountDialogActions("Confirm counts",!busy,{save(proposed())},{confirm=false})})
}
