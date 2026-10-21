package com.shifthud.frozen.count

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable internal fun DoorMapScreen(data:CountRouteData,busy:Boolean,error:String?,back:()->Unit,save:(List<RouteSegment>)->Unit) {
    var draft by remember {mutableStateOf(data.map.map {it.forSectionEditing()})}
    var review by remember {mutableStateOf(false)}
    var validation by remember {mutableStateOf<String?>(null)}
    fun update(segment:RouteSegment) {draft=draft.map {if(it.id==segment.id)segment else it}}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
        item {
            TextButton(onClick=back,enabled=!busy) {Text("Back to Count Route",Modifier.fillMaxWidth())}
            Text("Door map setup",Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall)
            Text("Only Frozen-owned locations enter the route. Add physical sections in walking order, including Meat or Other boundaries. Numbering skips non-Frozen sections. Leave uncertain ownership or counts unknown.",Modifier.fillMaxWidth())
            Text("Aisle 12: configure each actual Frozen side and its direction, or explicitly exclude an unused side. No layout is assumed.",Modifier.fillMaxWidth())
            Text("Aisle 12 walking order (when both sides are used)",Modifier.fillMaxWidth())
            listOf(false,true).forEach {leftFirst->Row {
                val label=if(leftFirst) "Left then Right" else "Right then Left"
                RadioButton((draft[4].side=="Left")==leftFirst,{draft=draft.take(4)+draft.takeLast(2).sortedBy {if((it.side=="Left")==leftFirst)0 else 1}},enabled=!busy,modifier=Modifier.semantics {contentDescription="Aisle 12 order: $label"})
                Text(label,Modifier.weight(1f))
            }}
            if(data.active!=null)Text("Active cycle ${data.active?.number} keeps its original map. Changes apply to the next cycle; no counts are removed.",Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primary)
            (validation ?: error)?.let {Text(it,Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.error)}
        }
        items(draft,key={it.id}) {segment->
            HorizontalDivider();Text("${segment.label} · ${segment.id}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium)
            Text(if(segment.excluded) "Excluded" else if(!segment.configured) "Not configured" else "Door numbers increase in your walking direction",Modifier.fillMaxWidth())
            if(segment.aisle==12) {
                Text(segment.direction?.label ?: "Direction not configured",Modifier.fillMaxWidth())
                WalkDirection.entries.forEach {direction->Row {
                    RadioButton(segment.direction==direction,{update(segment.copy(direction=direction))},enabled=!busy && !segment.excluded,modifier=Modifier.semantics {contentDescription="${segment.id} ${direction.label}"})
                    Text(direction.label,Modifier.weight(1f))
                }}
            } else Text(requireNotNull(segment.direction).label,Modifier.fillMaxWidth())
            Text("Sections in walking order: ${segment.direction?.label ?: "choose direction"}",Modifier.fillMaxWidth())
            segment.sections.orEmpty().forEachIndexed {index,section->
                key(section.id) {
                    SectionEditor(segment.id,index,section,!busy && !segment.excluded,
                        change={updated->update(segment.copy(sections=segment.sections!!.map {if(it.id==section.id)updated else it}))},
                        remove={update(segment.copy(sections=segment.sections!!.filterNot {it.id==section.id}))},
                        move=if(index==0)null else {{val list=segment.sections!!.toMutableList();list[index]=list[index-1];list[index-1]=section;update(segment.copy(sections=list))}})
                }
            }
            OutlinedButton(onClick={update(segment.copy(sections=segment.sections.orEmpty()+PhysicalSection(java.util.UUID.randomUUID().toString())))},enabled=!busy && !segment.excluded && segment.sections.orEmpty().size<100) {Text("Add section ${segment.id}",Modifier.fillMaxWidth())}
            Row {
                Checkbox(segment.excluded,{update(segment.copy(excluded=it))},enabled=!busy,modifier=Modifier.semantics {contentDescription="Exclude ${segment.id}"})
                Text("Explicitly exclude this segment",Modifier.weight(1f))
            }
        }
        item {
            (validation ?: error)?.let {Text(it,Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.error)}
            Button(enabled=!busy,onClick={
                try {
                    validateMap(draft);validation=null;review=true
                } catch(e:IllegalArgumentException) {validation=e.message}
            },modifier=Modifier.fillMaxWidth()) {Text("Review and save door map",Modifier.fillMaxWidth())}
            Text("Partial maps can be saved. Unclassified sections are never assumed Frozen. Meat and Other sections are excluded, not skipped or missing. For open cases, enter the number of distinct counting locations you identify during the walkthrough.",Modifier.fillMaxWidth())
        }
    }
    if(review)AlertDialog(onDismissRequest={if(!busy)review=false},title={Text("Confirm door map",Modifier.fillMaxWidth())},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        val changes=mapChanges(data.map,draft)
        if(changes.isEmpty())Text("No location changes.") else changes.forEach {Text(it,Modifier.fillMaxWidth().padding(vertical=4.dp))}
        val removed=generateDoors(data.map).map {it.id}.toSet()-generateDoors(draft).map {it.id}.toSet()
        val added=generateDoors(draft).map {it.id}.toSet()-generateDoors(data.map).map {it.id}.toSet()
        if(removed.isNotEmpty())Text("Removed from future routes: ${removed.joinToString()}",Modifier.fillMaxWidth())
        if(added.isNotEmpty())Text("Added to future routes: ${added.joinToString()}",Modifier.fillMaxWidth())
        Text("${draft.count {!it.configured}} segments not configured. All historical records are preserved.")
        if(data.active!=null)Text("Cycle ${data.active?.number} is unchanged, including its original locations and directions. Finish it explicitly and start a new cycle to use this map.")
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={CountDialogActions("Save map for future cycles",!busy,{save(draft)},{if(!busy)review=false},"Keep editing")})
}

@Composable private fun SectionEditor(segment:String,index:Int,section:PhysicalSection,enabled:Boolean,change:(PhysicalSection)->Unit,remove:()->Unit,move:(()->Unit)?) {
    var count by remember {mutableStateOf(section.count?.toString().orEmpty())}
    Text("Section ${index+1}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleSmall)
    OutlinedTextField(section.label,{change(section.copy(label=it))},enabled=enabled,label={Text("Section label (optional)",Modifier.fillMaxWidth())},modifier=Modifier.fillMaxWidth())
    SectionChoice("${segment} section ${index+1} department",section.department?.label ?: "Unknown",listOf("Unknown")+SectionDepartment.entries.map {it.label},enabled) {label->change(section.copy(department=SectionDepartment.entries.find {it.label==label}))}
    SectionChoice("${segment} section ${index+1} type",section.kind?.label ?: "Unknown",listOf("Unknown")+SectionKind.entries.map {it.label},enabled) {label->change(section.copy(kind=SectionKind.entries.find {it.label==label}))}
    OutlinedTextField(count,{count=it;change(section.copy(count=if(it.isBlank())null else it.toIntOrNull() ?: -1))},enabled=enabled,label={Text("$segment section ${index+1} location count",Modifier.fillMaxWidth())},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
    if(section.department==SectionDepartment.MEAT || section.department==SectionDepartment.OTHER)Text("Excluded from Frozen route; not missing or skipped.",Modifier.fillMaxWidth())
    move?.let {TextButton(onClick=it,enabled=enabled) {Text("Move section ${index+1} earlier",Modifier.fillMaxWidth())}}
    TextButton(onClick=remove,enabled=enabled) {Text("Remove section ${index+1}",Modifier.fillMaxWidth())}
}
@Composable private fun SectionChoice(label:String,value:String,options:List<String>,enabled:Boolean,select:(String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().semantics {contentDescription=label}) {Text("$label: $value",Modifier.fillMaxWidth())}
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {options.forEach {option->DropdownMenuItem(text={Text(option)},onClick={select(option);open=false})}}
    }
}
