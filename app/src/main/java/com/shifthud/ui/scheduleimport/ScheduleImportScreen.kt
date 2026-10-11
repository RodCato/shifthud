package com.shifthud.ui.scheduleimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.domain.scheduleimport.*
import com.shifthud.domain.model.ScheduledShift
import com.shifthud.ui.components.ExpandableSection
import com.shifthud.ui.schedule.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun ScheduleImportScreen(vm:ScheduleImportViewModel,back:()->Unit) {
    val raw by vm.raw.collectAsStateWithLifecycle()
    val encoded by vm.encoded.collectAsStateWithLifecycle()
    val weekText by vm.weekText.collectAsStateWithLifecycle()
    val confirmed by vm.confirmed.collectAsStateWithLifecycle()
    val existing by vm.schedule.collectAsStateWithLifecycle(initialValue=emptyList())
    val planning by vm.planning.collectAsStateWithLifecycle(initialValue=com.shifthud.domain.planner.PlannerSettings())
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val rows=remember(encoded){reviewRows(encoded)}
    val week=runCatching{LocalDate.parse(weekText)}.getOrNull()
    val extraction=remember(raw,week){extractSchedule(raw,week)}
    var weekPicker by rememberSaveable{mutableStateOf(false)}
    var confirmation by remember{mutableStateOf<List<ReviewRow>?>(null)}
    val picker=rememberLauncherForActivityResult(PickVisualMedia()){uri->if(uri!=null)vm.read(uri)}
    fun valid(row:ReviewRow):Boolean {
        val s=row.candidate.shift()?:return false
        return confirmed && week!=null && s.date in week..week.plusDays(6) &&
            s.plannedLunchMinutes!! in planning.minimumMinutes..240 && s.plannedLunchMinutes<Duration.between(s.start,s.end).toMinutes() &&
            (row.reviewed || row.candidate.confidence==Confidence.HIGH) &&
            classify(s,existing).kind in listOf(ImportKind.NEW,ImportKind.EXACT_DUPLICATE,ImportKind.POSSIBLE_UPDATE) &&
            (classify(s,existing).kind!=ImportKind.POSSIBLE_UPDATE || row.replace)
    }
    val selected=rows.filter{it.selected}
    val total=rows.filter{!it.candidate.off}.mapNotNull{it.candidate.shift()}.sumOf{Duration.between(it.start,it.end).toMinutes()-(it.plannedLunchMinutes?:0)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=back,enabled=!busy){Text("‹ SCHEDULE")}
        Text("Import Schedule Screenshot",style=MaterialTheme.typography.headlineMedium)
        Text("Local OCR · Review before saving. Images are not uploaded or kept.",style=MaterialTheme.typography.bodySmall)
        Button(enabled=!busy,onClick={picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))}){Text("SELECT IMAGE")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let{Text(it)}
        PickerField("Saturday–Friday workweek",week?.let{"$it – ${it.plusDays(6)}${if(confirmed) " · Confirmed" else " · Confirm year"}"}?:"Unknown · Confirm the screenshot's year and Saturday",!busy){weekPicker=true}
        Text("Changing the week resets review edits. Confirm the screenshot year.",style=MaterialTheme.typography.bodySmall)
        if(raw.isNotBlank())extraction.warnings.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
        if(rows.isNotEmpty()) {
            Text("Review projection: ${total/60}h ${total%60}m paid",style=MaterialTheme.typography.titleMedium)
            Text("Based on recognized review rows; unknown rows are excluded. Selected rows: ${selected.size}. Replacements retain existing lunch plans, which may change the saved total.",style=MaterialTheme.typography.bodySmall)
            extraction.weeklyNetMinutes?.let { net->
                Text("Screenshot weekly net: ${net/60}h ${net%60}m")
                if(net.toLong()!=total)Text("Mismatch: review rows differ from the screenshot total by ${kotlin.math.abs(net-total)} minutes. Cropped days may be missing.",color=MaterialTheme.colorScheme.error)
            }
            TextButton(enabled=!busy,onClick={vm.setRows(rows.map{it.copy(selected=valid(it))})}){Text("SELECT ALL VALID SHIFTS")}
            TextButton(enabled=!busy,onClick={vm.setRows(rows.map{it.copy(selected=false)})}){Text("CLEAR SELECTION")}
        }
        rows.forEachIndexed { index,row -> key(index) {
            ScheduleReviewRow(row,existing,valid(row),busy,planning.minimumMinutes,week) { updated ->vm.setRows(rows.toMutableList().also{it[index]=updated}) }
        } }
        OutlinedButton(enabled=!busy && rows.size<50,onClick={vm.setRows(rows+ReviewRow(ScheduleCandidate("Manually added review row",null,null,null,planning.plannedMinutes,LunchSource.DEFAULT,warnings=listOf("Choose date and times; saved default lunch proposal."))))}){Text("ADD MISSING SHIFT")}
        Button(enabled=!busy && selected.isNotEmpty() && selected.all(::valid),onClick={vm.capturePreview(existing);confirmation=rows}){Text("REVIEW IMPORT (${selected.size})")}
        if(raw.isNotEmpty())ExpandableSection("Recognized text","Original OCR, including store/department labels") { Text(raw,style=MaterialTheme.typography.bodySmall) }
    }
    if(weekPicker)ScheduleDatePicker(week?:LocalDate.now(),{weekPicker=false}){vm.confirmWeek(it);weekPicker=false}
    confirmation?.let { reviewed ->
        AlertDialog(onDismissRequest={if(!busy)confirmation=null},title={Text("Import confirmed shifts?")},text={Column(Modifier.verticalScroll(rememberScrollState())){
            Text("${reviewed.count{it.selected}} selected · $week – ${week?.plusDays(6)}")
            Text("Exact duplicates are skipped. ${reviewed.count{it.selected && it.replace}} replacements were explicitly chosen. Existing lunch plans and notes are preserved on replacements. Actual punches and payroll will not change.")
            Text("Review any weekly-total mismatch before confirming. Missing/cropped days are not assumed OFF.")
        }},confirmButton={TextButton(enabled=!busy,onClick={vm.save(reviewed){confirmation=null}}){Text("CONFIRM IMPORT")}},dismissButton={TextButton(enabled=!busy,onClick={confirmation=null}){Text("Cancel")}})
    }
}

@Composable private fun ScheduleReviewRow(row:ReviewRow,existing:List<ScheduledShift>,valid:Boolean,busy:Boolean,minimum:Int,week:LocalDate?,update:(ReviewRow)->Unit) {
    val c=row.candidate
    val match=classify(c.shift(),existing)
    val locale=LocalConfiguration.current.locales[0]
    val time=DateTimeFormatter.ofPattern("h:mm a",locale)
    var picker by rememberSaveable{mutableStateOf<String?>(null)}
    var lunchInput by rememberSaveable(c.lunch){mutableStateOf(c.lunch?.toString()?:"")}
    val label="${c.date?:"Unknown date"} · ${if(c.off) "OFF (explicit)" else "${c.start?.format(time)?:"?"} – ${c.end?.format(time)?:"?"}"}"
    val lunchLabel=when(c.lunchSource){LunchSource.EXPLICIT->"shown";LunchSource.INFERRED_NET->"inferred";LunchSource.DEFAULT->"default proposal";LunchSource.CORRECTED->"corrected"}
    ExpandableSection(label,"${if(row.selected) "Selected · " else ""}${c.confidence.name.replace('_',' ')} · ${if(c.off) "No schedule to save" else "${match.kind.name.replace('_',' ')} · Lunch ${c.lunch?:"?"}m ($lunchLabel)"}") {
        if(!c.off) {
            Row { Checkbox(modifier=Modifier.semantics{contentDescription="Select $label"},checked=row.selected,enabled=!busy && (valid || row.selected),onCheckedChange={update(row.copy(selected=it))});Text("Select this shift",Modifier.padding(top=12.dp)) }
            PickerField("Date · ${if(c.date==null) "Unknown" else if(c.weekday!=null && c.date.dayOfWeek!=c.weekday) "Review required" else "High confidence"}",c.date?.toString()?:"Unknown",!busy){picker="date"}
            PickerField("Start · ${if(c.start==null) "Unknown" else if(c.warnings.any{it.contains("column",true) || it.contains("Multiple time")}) "Review required" else "High confidence"}",c.start?.format(time)?:"Unknown · Choose AM/PM",!busy){picker="start"}
            PickerField("End · ${if(c.end==null) "Unknown" else if(c.warnings.any{it.contains("column",true) || it.contains("Multiple time")}) "Review required" else "High confidence"}",c.end?.format(time)?:"Unknown · Choose AM/PM",!busy){picker="end"}
            Text("End at or before start is on the following day.",style=MaterialTheme.typography.bodySmall)
            Text(when(c.lunchSource){LunchSource.EXPLICIT->"Lunch explicitly displayed";LunchSource.INFERRED_NET->"Lunch inferred from displayed net hours";LunchSource.DEFAULT->"Saved default lunch proposal · Not employer-specified";LunchSource.CORRECTED->"Lunch corrected in review"})
            OutlinedTextField(lunchInput,{lunchInput=it;update(row.copy(candidate=c.copy(lunch=it.toIntOrNull(),lunchSource=LunchSource.CORRECTED),reviewed=false,selected=false))},label={Text("Planned lunch minutes ($minimum–240)")},singleLine=true,enabled=!busy)
            c.netMinutes?.let{Text("Displayed net: ${it/60}h ${it%60}m")}
            c.warnings.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
            if(c.date!=null && week!=null && c.date !in week..week.plusDays(6))Text("Date is outside the selected week.",color=MaterialTheme.colorScheme.error)
            if(match.existing.isNotEmpty()) {
                Text("Existing: "+match.existing.joinToString { "${it.date} ${it.scheduledStart.format(time)}–${it.scheduledEnd.format(time)} · lunch ${it.plannedLunchMinutes?:"default"} min" })
                Text("Screenshot: ${c.start?.format(time)?:"?"}–${c.end?.format(time)?:"?"}")
                if(match.kind==ImportKind.POSSIBLE_UPDATE) {
                    Row { Checkbox(modifier=Modifier.semantics{contentDescription="Replace existing schedule for $label"},checked=row.replace,enabled=!busy,onCheckedChange={update(row.copy(replace=it,selected=false))});Text("Replace schedule · Keep existing lunch plan",Modifier.weight(1f).padding(top=12.dp)) }
                    Text("Leave unchecked to keep existing / skip. Worked or active schedules cannot be replaced.")
                }
                if(match.kind==ImportKind.CONFLICT)Text("Overlapping records require manual schedule review. This row cannot be imported.")
                if(match.kind==ImportKind.EXACT_DUPLICATE)Text("Duplicate will be skipped; existing lunch override is kept.")
            }
            Row { Checkbox(modifier=Modifier.semantics{contentDescription="Acknowledge review for $label"},checked=row.reviewed,enabled=!busy,onCheckedChange={update(row.copy(reviewed=it,selected=false))});Text("I reviewed dates, AM/PM, lunch and warnings",Modifier.weight(1f).padding(top=12.dp)) }
        } else Text("Explicit day off is informational; it does not delete schedules or create work records.")
        ExpandableSection("Row source","Original recognized values") { Text(c.raw,style=MaterialTheme.typography.bodySmall) }
    }
    if(picker=="date")ScheduleDatePicker(c.date?:week?:LocalDate.now(),{picker=null}){update(row.copy(candidate=c.copy(date=it),selected=false,reviewed=false));picker=null}
    if(picker=="start" || picker=="end") {
        val start=picker=="start"
        ScheduleTimePicker(if(start)"Start time" else "End time",(if(start)c.start else c.end)?:LocalTime.NOON,{picker=null}) {
            update(row.copy(candidate=if(start)c.copy(start=it) else c.copy(end=it),selected=false,reviewed=false));picker=null
        }
    }
}
