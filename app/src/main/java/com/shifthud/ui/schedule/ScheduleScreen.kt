package com.shifthud.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shifthud.domain.model.*
import com.shifthud.ui.*
import java.time.*

@Composable fun ScheduleScreen(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean, importScreenshot: () -> Unit = {}) {
    val app=androidx.compose.ui.platform.LocalContext.current.applicationContext as com.shifthud.ShiftHudApplication
    val planning by app.preferences.weeklyPlannerSettings.collectAsState(initial=null)
    val target by app.preferences.weeklyTargetSettings.collectAsState(initial=null)
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lunchWeek by rememberSaveable { mutableStateOf<Long?>(null) }
    val today = data.now.atZone(ZoneId.systemDefault()).toLocalDate()
    var selectedWeek by rememberSaveable { mutableStateOf(scheduleWeek(today).start.toEpochDay()) }
    val week=scheduleWeek(LocalDate.ofEpochDay(selectedWeek))
    val locale=LocalConfiguration.current.locales[0]
    val zone=ZoneId.systemDefault()
    val recordsFlow=remember(week.start,zone) { app.repository.sessionsBetween(week.start,week.endInclusive.plusDays(1),zone) }
    val records by key(week.start,zone) { recordsFlow.collectAsState(initial=null) }
    val scheduledWeek=remember(data.schedule,week) { week.scheduleRows(data.schedule) }
    val ids=scheduledWeek.map { it.id }
    val linkedFlow=remember(ids) { app.repository.scheduledSessions(ids) }
    val linkedRecords by key(ids) { linkedFlow.collectAsState(initial=null) }
    val completion=linkedRecords?.let { scheduleCompletion(scheduledWeek,it) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("schedule-list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Schedule",style=MaterialTheme.typography.headlineMedium)
        com.shifthud.ui.components.SectionHeading("Employer schedule")
        Text(week.scheduleLabel(locale),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("selected-schedule-week").semantics { heading() })
        Text("Saturday–Friday · ${scheduledWeek.size} scheduled shifts",style=MaterialTheme.typography.bodySmall)
        Text(completion?.summary ?: "Loading completion…",style=MaterialTheme.typography.bodyMedium,
            modifier=Modifier.testTag("schedule-completion-summary"))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={selectedWeek=week.moveWeeks(-1).start.toEpochDay()},modifier=Modifier.weight(1f).semantics { contentDescription="Previous week" }) { Text("‹ Previous week") }
            TextButton(onClick={selectedWeek=week.moveWeeks(1).start.toEpochDay()},modifier=Modifier.weight(1f).semantics { contentDescription="Next week" }) { Text("Next week ›") }
        }
        } }
        item { Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        val settings=planning
        val targetSettings=target
        val loadedRecords=records
        if(data.loaded && settings!=null && targetSettings!=null && loadedRecords!=null) {
            val summary=schedulePlanning(week,scheduledWeek,loadedRecords,settings,targetSettings.target,data.now,zone,data.pay.rates)
            SchedulePlanningSummary(summary,settings.weeklyMinutes(week.start),locale)
            OutlinedButton(onClick={lunchWeek=week.start.toEpochDay()},enabled=!busy) { Text("EDIT WEEKLY LUNCH PLAN") }
        } else Text("Loading weekly planning…")
        } }
        item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={editingId=0},enabled=data.loaded && !busy) { Text("ADD SHIFT") }
        OutlinedButton(onClick=importScreenshot,enabled=data.loaded && !busy) { Text("Import Schedule Screenshot") }
        } }
        if(!data.loaded) item { CircularProgressIndicator() }
        else {
            if(scheduledWeek.isEmpty()) item { Text("No scheduled shifts in this week.",modifier=Modifier.padding(vertical=8.dp)) }
            val dates=(0L..6L).map{week.start.plusDays(it)}
            items(dates,key={it.toEpochDay()}) { date ->
                val shifts=scheduledWeek.filter{it.date==date}
                if(shifts.isEmpty()) {
                    val worked=records.orEmpty().filter{it.clockIn.atZone(zone).toLocalDate()==date}
                    Text("${date.format(dateFormat)} · ${if(worked.any{it.clockOut==null}) "Active · Unscheduled" else if(worked.isNotEmpty()) "Worked · Unscheduled" else "No shift saved"}",modifier=Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodyMedium)
                }
                shifts.forEach { shift -> key(shift.id) {
                    val status=completion?.states?.get(shift.id)
                    val lunch=planning?.weeklyMinutes(shift.date)
                    val actuals=linkedRecords?.let { scheduleActuals(shift,it,lunch,data.now,zone,locale,android.text.format.DateFormat.is24HourFormat(app)) }
                    com.shifthud.ui.components.ExpandableSection("${shift.date.format(dateFormat)}\n${shift.timeLabel()}",status?.label ?: "Loading completion…",
                        leading={ status?.let { ScheduleCompletionIndicator(it) } },
                        subtitleContent={ ScheduleActualSummary(actuals) }) {
                        actuals?.intervals?.forEach { Text("Lunch: $it",style=MaterialTheme.typography.bodySmall) }
                        shift.notes?.let{Text(it)}
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={editingId=shift.id},enabled=!busy) { Text("Edit shift") }
                            TextButton(onClick={deletingId=shift.id},enabled=!busy) { Text("Delete shift") }
                        }
                        com.shifthud.ui.components.ExpandableSection("Advanced schedule details","Employer metadata and retained lunch values") {
                            Text("Weekly projection lunch: $lunch minutes · Edit Weekly Lunch Plan above")
                            shift.plannedLunchMinutes?.let { Text("Legacy lunch value: $it minutes · Retained for reference") }
                            shift.employerNetMinutes?.let { Text("Employer reported paid time: ${it/60}h ${it%60}m") }
                            shift.employerLunchMinutes?.let { Text("Employer lunch: $it minutes · Schedule metadata") }
                        }
                    }
                } }
            }
        }
    }
    lunchWeek?.let { epoch ->
        com.shifthud.ui.settings.LunchPlanDialog(vm,busy,LocalDate.ofEpochDay(epoch)) { lunchWeek=null }
    }
    editingId?.let { id ->
        val existing = data.schedule.firstOrNull { it.id == id }
        key(id) { ShiftEditor(existing, if(today in week) today else week.start, busy, { editingId = null }) { vm.save(it) { editingId = null } } }
    }
    deletingId?.let { id -> AlertDialog(onDismissRequest = { if (!busy) deletingId = null }, title = { Text("Delete scheduled shift?") }, text = { Text("Any recorded work session will be kept, without its schedule association.") }, confirmButton = { TextButton(onClick = { vm.delete(id) { deletingId = null } }, enabled = !busy) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletingId = null }, enabled = !busy) { Text("Cancel") } }) }
}

@Composable private fun ShiftEditor(existing: ScheduledShift?, today: LocalDate, busy: Boolean, dismiss: () -> Unit, save: (ScheduledShift) -> Unit) {
    val valuesSaver = listSaver<SchedulePickerValues, Long>(
        save = { listOf(it.date.toEpochDay(), it.start.toNanoOfDay(), it.end.toNanoOfDay()) },
        restore = { SchedulePickerValues(LocalDate.ofEpochDay(it[0]), LocalTime.ofNanoOfDay(it[1]), LocalTime.ofNanoOfDay(it[2])) },
    )
    var values by rememberSaveable(stateSaver = valuesSaver) { mutableStateOf(SchedulePickerValues.initial(existing, today)) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (existing == null) "Add shift" else "Edit shift") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerField("Date", values.date.editorLabel(locale), !busy) { picker = "date" }
            PickerField("Start", values.start.editorLabel(locale), !busy) { picker = "start" }
            PickerField("End", values.end.editorLabel(locale), !busy) { picker = "end" }
            Text("An end at or before start means the next day.")
            OutlinedTextField(notes, { notes = it }, label = { Text("Note (optional)") }, maxLines = 3)
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            val shift = (existing ?: ScheduledShift(date=values.date,scheduledStart=values.start,scheduledEnd=values.end)).copy(
                date=values.date,scheduledStart=values.start,scheduledEnd=values.end,notes=notes.trim().ifBlank { null })
            save(shift)
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
    when (picker) {
        "date" -> ScheduleDatePicker(values.date, { picker = null }) {
            values = values.copy(date = it)
            picker = null
        }
        "start", "end" -> {
            val editingStart = picker == "start"
            ScheduleTimePicker(if (editingStart) "Start time" else "End time", if (editingStart) values.start else values.end, { picker = null }) {
                values = if (editingStart) values.copy(start = it) else values.copy(end = it)
                picker = null
            }
        }
    }
}

@Composable internal fun ScheduleActualSummary(actuals: ScheduleActuals?) {
    Text(actuals?.summary ?: "Loading completion…",modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium,
        fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
    actuals?.lunch?.let { Text(it,modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
}
