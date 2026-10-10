package com.shifthud.ui.importing

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.domain.importing.*
import com.shifthud.domain.calendar.recordDuration
import java.time.*
import java.time.format.*

@Composable fun HistoricalImportScreen(vm:HistoricalImportViewModel,back:()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val zoneId by vm.zone.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var zoneText by rememberSaveable(zoneId){mutableStateOf(zoneId)}
    var zoneError by rememberSaveable{mutableStateOf<String?>(null)}
    var confirming by rememberSaveable{mutableStateOf(false)}
    var zoneConfirmed by rememberSaveable{mutableStateOf(false)}
    val locale=LocalConfiguration.current.locales[0]
    val dates=DateTimeFormatter.ofPattern("EEE, MMM d, yyyy",locale)
    val times=DateTimeFormatter.ofPattern(if(DateFormat.is24HourFormat(LocalContext.current))"HH:mm" else "h:mm a",locale)
    fun time(t:LocalTime)=t.format(times)
    val selected=state.rows.filter{it.status==BackfillStatus.READY && it.candidate.date in state.selected}.map{it.candidate.date}.toSet()
    val allRecorded=state.rows.size==6 && state.rows.all{it.status==BackfillStatus.DUPLICATE}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick=back,enabled=!busy){Text("‹ SETTINGS")}
            Text("Historical Publix Shifts",style=MaterialTheme.typography.headlineMedium)
            Text("September 25 – October 2, 2026 · 6 shifts available")
            Text("Review the Passport punches below, then select records to import. Existing records are always skipped, never replaced.",style=MaterialTheme.typography.bodyMedium)
        }
        item {
            Text("Work-location time zone",style=MaterialTheme.typography.titleMedium)
            Text("Starts with this device’s zone. Confirm it matches the store’s local time before importing.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(zoneText,{zoneText=it;zoneError=null},label={Text("Time zone ID")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedButton(enabled=!busy,onClick={zoneError=vm.zone(zoneText)}){Text("APPLY TIME ZONE")}
            zoneError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Text("Preview zone: ${state.zone.id}",style=MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Minute-precision punches determine paid time; employer decimals are separate and may differ. No punches will be adjusted to match payroll.")
            Text("Passport weekly totals: Sep 19–25 · 2.15 h; Sep 26–Oct 2 · 35.38 h. Paystub for the second week separately reports 35.39 h. No paycheck or deposit is created.",style=MaterialTheme.typography.bodySmall)
            Text("Store numbers are shown here only. WorkSession has no store metadata field.",style=MaterialTheme.typography.bodySmall)
            Text("Estimates use your existing historical rate settings ($16/hour in the supplied reference); this import does not change rates.",style=MaterialTheme.typography.bodySmall)
        }
        state.error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        if(state.rows.isEmpty() && state.error==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        items(state.rows,key={it.candidate.date.toString()}) { row ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val input=row.candidate.input
                Row(Modifier.fillMaxWidth().toggleable(value=input.date in selected,enabled=!busy && row.status==BackfillStatus.READY,role=Role.Checkbox,onValueChange={vm.select(input.date,it)}),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(input.date in selected,null,enabled=!busy && row.status==BackfillStatus.READY)
                    Text(input.date.format(dates),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                }
                Text("Store ${row.candidate.store} · ${time(input.clockIn)} – ${time(input.clockOut)}")
                Text(if(input.lunchStart==null)"No lunch" else "Lunch: ${time(input.lunchStart)} – ${time(input.lunchEnd!!)}")
                Text("Calculated paid time: ${recordDuration(row.paid)}")
                Text("Passport reported: ${row.candidate.employerHours} decimal hours",style=MaterialTheme.typography.bodySmall)
                Text(row.status.label,color=MaterialTheme.colorScheme.primary)
                row.conflicts.forEach{existing ->
                    fun punch(t:Instant)=t.atZone(state.zone).let{ "${it.toLocalDate().format(dates)} ${it.toLocalTime().format(times)}" }
                    Text("Existing #${existing.id}${if(existing.manuallyEntered)" · manually entered" else ""}\n${punch(existing.clockIn)} – ${existing.clockOut?.let(::punch)?:"still active"}\nLunch: ${existing.lunchStart?.let(::punch)?:"none"} – ${existing.lunchEnd?.let(::punch)?:"none"}",style=MaterialTheme.typography.bodySmall)
                }
            } }
        }
        item {
            if(allRecorded)Text("All historical shifts already recorded." + if(message==null) "\n0 new shifts imported." else "",style=MaterialTheme.typography.titleMedium)
            message?.let{Text(it,style=MaterialTheme.typography.titleMedium)}
            Text("${selected.size} selected · ${state.rows.count{it.status!=BackfillStatus.READY}} existing/conflicting records skipped")
            Button(enabled=!busy && selected.isNotEmpty() && state.error==null && state.zone.id==zoneId && zoneText.trim()==zoneId,onClick={zoneConfirmed=false;confirming=true}){Text(if(busy)"IMPORTING…" else "REVIEW IMPORT")}
            if(zoneText.trim()!=zoneId)Text("Apply the time zone before continuing.",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(confirming)AlertDialog(onDismissRequest={if(!busy)confirming=false},title={Text("Import ${selected.size} historical shifts?")},text={Column {
        Text("Only selected, conflict-free records will be added. Conflicts are checked again at import. Existing shifts and payroll facts will remain untouched.")
        Row(Modifier.fillMaxWidth().toggleable(zoneConfirmed,role=Role.Checkbox,onValueChange={zoneConfirmed=it}),verticalAlignment=Alignment.CenterVertically){Checkbox(zoneConfirmed,null);Text("${state.zone.id} is the work location’s time zone.",Modifier.weight(1f))}
    }},confirmButton={TextButton(enabled=zoneConfirmed && selected.isNotEmpty() && !busy,onClick={confirming=false;vm.import(selected,state.zone)}){Text("CONFIRM IMPORT")}},dismissButton={TextButton(onClick={confirming=false}){Text("CANCEL")}})
}
