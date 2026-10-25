package com.shifthud.frozen.count

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.ui.components.ExpandableSection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun dateTime(value:Instant)=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(value)

@Composable fun CountRouteScreen(vm:CountRouteViewModel,back:()->Unit) {
    val data by vm.data.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle();val error by vm.error.collectAsStateWithLifecycle()
    var mapOpen by rememberSaveable {mutableStateOf(false)}
    val current=data
    if(mapOpen && current!=null) {
        BackHandler {mapOpen=false}
        DoorMapScreen(current,busy,error,{mapOpen=false}) {vm.saveMap(current,it) {mapOpen=false}}
    } else CountRouteContent(current,busy,error,vm,back,{mapOpen=true})
}

@Composable private fun CountRouteContent(data:CountRouteData?,busy:Boolean,error:String?,vm:CountRouteViewModel,back:()->Unit,openMap:()->Unit) {
    var targetOpen by remember {mutableStateOf(false)}
    var resolving by remember {mutableStateOf<DoorState?>(null)}
    var finish by remember {mutableStateOf(false)}
    var confirmLegacy by remember {mutableStateOf(false)}
    var editingSession by remember {mutableStateOf<Pair<Int,CountSession>?>(null)}
    val cycle=data?.active
    val dark=androidx.compose.foundation.isSystemInDarkTheme()
    val amber=if(dark)Color(0xFFFFD180) else Color(0xFF795000)
    val blue=if(dark)Color(0xFF9BCBFF) else Color(0xFF17538C)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
        item {
            TextButton(onClick=back) {Text("Back to Frozen Tasks",Modifier.fillMaxWidth())}
            Text("FROZEN COUNT ROUTE",Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall)

            error?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick=vm::clearError) {Text("Dismiss message",Modifier.fillMaxWidth())}}
        }
        if(data==null)item {Text("Loading Count Route…",Modifier.fillMaxWidth())}
        else {
            if(cycle!=null) {
                item {
                    val door=cycle.currentDoor
                    if(door!=null) {
                        Text(if(cycle.paused) "Continue ${cycle.location(door)} — ${door.products}/${cycle.target}" else cycle.location(door),Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall,color=amber)
                        Text("${door.id} · ${cycle.map.single {it.id==door.segmentId}.direction?.label}",Modifier.fillMaxWidth())
                        Text("Products counted: ${door.products} / ${cycle.target}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleLarge)
                        val segment=cycle.doors.filter {it.segmentId==door.segmentId}
                        Text("Segment: ${segment.count {it.state==DoorState.SAMPLED}} / ${segment.size} locations sampled",Modifier.fillMaxWidth())
                        LinearProgressIndicator(progress={segment.count {it.state==DoorState.SAMPLED}.toFloat()/segment.size},modifier=Modifier.fillMaxWidth().semantics {contentDescription="Current segment sampling progress"})
                        Text("Target is a guide; fewer or more is OK.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
                        if(!cycle.paused && cycle.ownershipKnown) {
                            Button(onClick={vm.count(data)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {Text("+1 Count",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleLarge)}
                            OutlinedButton(onClick={vm.count(data,true)},enabled=!busy && door.products>0,modifier=Modifier.fillMaxWidth()) {Text("Undo",Modifier.fillMaxWidth())}
                            Button(onClick={vm.resolve(data,DoorState.SAMPLED,door.note) {}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Complete Door / Next Door",Modifier.fillMaxWidth())}
                            OutlinedButton(onClick={resolving=DoorState.SKIPPED},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Skip Location",Modifier.fillMaxWidth())}
                        }
                        val next=cycle.nextOpen(door.id)
                        Text(next?.let {"Next: ${cycle.location(it)}"} ?: "Next: Review skipped locations or finish this cycle",Modifier.fillMaxWidth(),color=blue)
                    } else Text("No pending door selected. Revisit skipped locations below or finish the counting cycle.",Modifier.fillMaxWidth())
                    if(cycle.paused)Button(onClick={vm.resume(data)},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Resume",Modifier.fillMaxWidth())}
                    else OutlinedButton(onClick={vm.pause(data)},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Pause",Modifier.fillMaxWidth())}
                    Text(if(cycle.paused) "Paused · progress saved" else "Each action is saved automatically",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
                }
                item {
                    Text("Cycle ${cycle.number} · Started ${dateTime(cycle.started)}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.labelLarge)
                    Coverage(cycle)
                    if(!cycle.ownershipKnown) {
                        Text("Confirm the saved route before continuing. Counts and position are preserved.",Modifier.fillMaxWidth(),color=amber)
                        TextButton(onClick={confirmLegacy=true}) {Text("Confirm existing route",Modifier.fillMaxWidth())}
                    }
                    if(cycle.map!=data.map)Text("Map edits are saved for the next cycle. This cycle keeps its original door map.",Modifier.fillMaxWidth(),color=blue)
                }
            }
            item {
                OutlinedButton(onClick=openMap,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Door Map Setup",Modifier.fillMaxWidth())}
                TextButton(onClick={targetOpen=true},enabled=!busy) {Text("Target: ${data.target} products per location · Change",Modifier.fillMaxWidth())}
                if(cycle==null) {
                    Text("${simplifyDoorMap(data.map).map.let(::generateDoors).size} configured Frozen doors · ${data.map.count {!it.configured}} segments not configured",Modifier.fillMaxWidth())
                    if(!data.map.all {it.configured})Text("Partial map — unknown segments can be filled in later.",Modifier.fillMaxWidth())
                    Button(onClick={vm.start(data)},enabled=!busy && isSimpleMap(data.map) && generateDoors(data.map).isNotEmpty(),modifier=Modifier.fillMaxWidth()) {Text("Start New Counting Cycle",Modifier.fillMaxWidth())}
                    if(!isSimpleMap(data.map))Text("Review old counts in Door Map Setup before starting a new cycle.",Modifier.fillMaxWidth()) else if(generateDoors(data.map).isEmpty())Text("Enter at least one Frozen door to begin.",Modifier.fillMaxWidth())
                }
            }
            if(cycle!=null) {
                item {Text("Route coverage",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleLarge)}
                items(cycle.map,key={it.id}) {segment->
                    val doors=cycle.doors.filter {it.segmentId==segment.id}
                    ExpandableSection(segment.label,if(segment.excluded) "Explicitly excluded" else if(!segment.configured) "Not configured" else "${segment.direction?.label} · ${doors.count {it.state==DoorState.SAMPLED}} / ${doors.size} sampled") {
                        doors.forEach {door->
                            val status=if(door.state==DoorState.OPEN && door.products>0) "Partial" else door.state.label
                            val color=when {door.state==DoorState.SAMPLED->MaterialTheme.colorScheme.primary;door.state==DoorState.SKIPPED->MaterialTheme.colorScheme.error;door.products>0 || door.id==cycle.current->amber;else->MaterialTheme.colorScheme.onSurfaceVariant}
                            Text("${cycle.location(door)} · ${door.id} · $status · ${door.products} products",Modifier.fillMaxWidth(),color=color)
                            if(door.note.isNotBlank())Text(door.note,Modifier.fillMaxWidth())
                            if(door.state!=DoorState.SAMPLED && door.id!=cycle.current)TextButton(onClick={vm.revisit(data,door.id)},enabled=!busy && !cycle.paused && cycle.ownershipKnown) {Text("${if(door.state==DoorState.SKIPPED) "Revisit" else "Continue"} ${door.id}")}
                        }
                    }
                }
                item {
                    Button(onClick={finish=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Finish Counting Cycle",Modifier.fillMaxWidth())}
                    ExpandableSection("Sessions & manual approval","Separate from physical route progress") {
                        cycle.sessions.reversed().forEach {s->SessionRow(s) {editingSession=cycle.number to s}}
                    }
                }
            }
            item {Text("Previous cycles",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleLarge);if(data.cycles.none {it.finished!=null})Text("No finished cycles yet.",Modifier.fillMaxWidth())}
            items(data.cycles.filter {it.finished!=null}.reversed(),key={"cycle-${it.number}"}) {old->
                ExpandableSection("Cycle ${old.number}","${dateTime(requireNotNull(old.finished))} · ${if(old.covered) "Configured route sampled" else "Partial coverage"}") {
                    Text("Started ${dateTime(old.started)} · Target ${old.target}",Modifier.fillMaxWidth());Coverage(old)
                    old.doors.forEach {Text("${old.location(it)} · ${it.id} · ${it.state.label} · ${it.products} products${if(it.note.isBlank()) "" else " · ${it.note}"}",Modifier.fillMaxWidth())}
                    old.sessions.forEach {s->SessionRow(s) {editingSession=old.number to s}}
                }
            }
        }
    }
    if(data!=null && targetOpen) {
        var text by remember {mutableStateOf(data.target.toString())};val target=text.toIntOrNull()
        AlertDialog(onDismissRequest={targetOpen=false},title={Text("Products-per-door target",Modifier.fillMaxWidth())},text={Column {
            Text("A guide only; never automatically completes a door. Updates this active cycle and future cycles.")
            OutlinedTextField(text,{text=it},label={Text("Products per door (1–100)",Modifier.fillMaxWidth())},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        }},confirmButton={CountDialogActions("Save target",!busy && target!=null && target in 1..100,{vm.target(data,requireNotNull(target)) {targetOpen=false}},{targetOpen=false})})
    }
    if(data!=null && cycle!=null && confirmLegacy)Confirm("Continue saved route?","Confirm that every location in this saved route is a Frozen door. Existing counts, location identifiers and session history will stay unchanged. If unsure, cancel and keep the old route preserved.","Confirm Frozen route",busy,{confirmLegacy=false}) {vm.confirmExistingRoute(data);confirmLegacy=false}

    if(data!=null && cycle!=null && resolving!=null) {
        var note by remember {mutableStateOf(cycle.currentDoor?.note.orEmpty())};val state=requireNotNull(resolving)
        AlertDialog(onDismissRequest={resolving=null},title={Text(if(state==DoorState.SAMPLED) "Confirm location sampled" else "Skip this location?")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${cycle.current} · ${cycle.currentDoor?.products} products counted. "+if(state==DoorState.SAMPLED) "Fewer than the target is allowed. This does not mean every SKU was counted." else "Skipped locations remain visible and do not count as sampled.")
            OutlinedTextField(note,{note=it},label={Text("Location note (optional)",Modifier.fillMaxWidth())},modifier=Modifier.fillMaxWidth())
        }},confirmButton={CountDialogActions(if(state==DoorState.SAMPLED) "Mark sampled" else "Mark skipped",!busy && note.length<=1000,{vm.resolve(data,state,note) {resolving=null}},{resolving=null})})
    }
    if(data!=null && cycle!=null && finish)Confirm("Finish Counting Cycle?","${cycle.sampled}/${cycle.doors.size} locations sampled · ${cycle.products} products · ${cycle.skipped} skipped · ${cycle.partial} partial · ${cycle.doors.count {it.state==DoorState.OPEN && it.products==0}} not visited.\n"+
        (if(cycle.covered) "Configured route sampled." else "Partial coverage. Unresolved doors and incomplete map segments remain in history; this is not full-department coverage.")+"\nA new cycle starts only when you choose to start one.","Finish and keep history",busy,{finish=false}) {vm.finish(data) {finish=false}}
    if(data!=null)editingSession?.let { (number,session)->
        var note by remember(session.id) {mutableStateOf(session.note)};var status by remember(session.id) {mutableStateOf(session.submission)}
        AlertDialog(onDismissRequest={editingSession=null},title={Text("Manual submission status",Modifier.fillMaxWidth())},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Personal record only. ShiftHUD cannot submit or verify official inventory approval.")
            Submission.entries.forEach {option->Row {RadioButton(status==option,{status=option},modifier=Modifier.semantics {contentDescription=option.label});Text(option.label,Modifier.weight(1f))}}
            OutlinedTextField(note,{note=it},label={Text("Session note (optional)",Modifier.fillMaxWidth())},modifier=Modifier.fillMaxWidth())
        }},confirmButton={CountDialogActions("Save manual status",!busy && note.length<=1000,{vm.submission(data,number,session.id,status,note) {editingSession=null}},{editingSession=null})})
    }
}

@Composable private fun Coverage(c:CountCycle) {
    Text("${c.sampled} / ${c.doors.size} locations sampled · ${c.products} products counted",Modifier.fillMaxWidth())
    Text("${c.skipped} skipped · ${c.partial} partial · ${c.map.count {!it.configured}} segments not configured",Modifier.fillMaxWidth())
    if(!c.mapComplete)Text("Map incomplete — full route coverage cannot be claimed.",Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.error)
}
@Composable private fun SessionRow(session:CountSession,edit:()->Unit) {
    Text("${dateTime(session.started)} – ${session.ended?.let(::dateTime) ?: "Active"}\n${session.startDoor ?: "Route review"} → ${session.endDoor ?: if(session.ended!=null) "Route review" else "In progress"}\n${session.products} net counts recorded · ${session.submission.label}",Modifier.fillMaxWidth())
    if(session.note.isNotBlank())Text(session.note,Modifier.fillMaxWidth())
    TextButton(onClick=edit) {Text("Edit manual status · ${dateTime(session.started)}",Modifier.fillMaxWidth())}
}
@Composable private fun Confirm(title:String,message:String,action:String,busy:Boolean,dismiss:()->Unit,confirm:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text(title,Modifier.fillMaxWidth())},text={Text(message,Modifier.verticalScroll(rememberScrollState()))},confirmButton={CountDialogActions(action,!busy,confirm,dismiss)})
}

/** One vertically measured action column avoids AlertDialog flow-row overlap at 200% text. */
@Composable internal fun CountDialogActions(label:String,enabled:Boolean,confirm:()->Unit,dismiss:()->Unit,cancelLabel:String="Cancel") {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick=confirm,enabled=enabled,modifier=Modifier.fillMaxWidth()) {Text(label,Modifier.fillMaxWidth())}
        TextButton(onClick=dismiss,modifier=Modifier.fillMaxWidth()) {Text(cancelLabel,Modifier.fillMaxWidth())}
    }
}
