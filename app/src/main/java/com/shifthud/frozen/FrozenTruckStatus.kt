package com.shifthud.frozen

import android.text.format.DateFormat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.shifthud.ui.components.ExpandableSection

@Composable internal fun FrozenTruckStatus(truck:FrozenTruckWorkflow?,busy:Boolean,arrival:(Boolean)->Unit) {
    var undo by remember(truck?.date) { mutableStateOf(false) }
    val locale=LocalConfiguration.current.locales[0]
    val use24Hour=DateFormat.is24HourFormat(LocalContext.current)
    val event=truck?.arrival
    val amber=if(isSystemInDarkTheme()) Color(0xFFE5BF75) else Color(0xFF795500)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(if(truck==null) "Loading truck status…" else if(event==null) "TRUCK: NOT ARRIVED" else "TRUCK: ARRIVED — ${truckArrivalText(event,locale,use24Hour)}",
            Modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite },style=MaterialTheme.typography.titleMedium,
            color=if(event==null) amber else MaterialTheme.colorScheme.primary)
        if(truck!=null && event==null) {
            Text("Waiting for Frozen delivery",Modifier.fillMaxWidth(),color=amber)
            Button(onClick={arrival(true)},enabled=!busy) { Text("TRUCK ARRIVED",Modifier.fillMaxWidth()) }
            ExpandableSection("While waiting","Optional work · no delivery time assumed") {
                Text("Backstock, ad-wall replenishment, missing price tags, MASC review, damages and donations, Random Counts, freezer/window cleaning, and general organization.",Modifier.fillMaxWidth())
                Text("Suggestions only—no extra tasks are created. Scanner-dependent work is available when equipment can be obtained. All phases remain available in any order.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
            }
        } else if(event!=null) {
            Text("Receiving and stocking are shown first. Task progress is unchanged.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={undo=true},enabled=!busy) { Text("Correct arrival / mark not arrived",Modifier.fillMaxWidth()) }
        }
        if(truck!=null && truck.events.isNotEmpty()) ExpandableSection("Truck event history","${truck.events.size} recorded events") {
            truck.events.asReversed().forEach { item ->
                val local=item.at.atZone(java.time.ZoneId.of(item.zone))
                Text("${if(item.arrived) "Arrived" else "Arrival undone"} · ${local.toLocalDate()} · ${truckArrivalText(item,locale,use24Hour)} (${item.zone})",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(undo) AlertDialog(onDismissRequest={undo=false},title={Text("Undo truck arrival?")},text={Text("Return this workflow to Not arrived. Task progress and the original arrival event remain in history.")},
        confirmButton={TextButton(enabled=!busy,onClick={arrival(false);undo=false}) { Text("Mark not arrived") }},dismissButton={TextButton(onClick={undo=false}) { Text("Cancel") }})
}
