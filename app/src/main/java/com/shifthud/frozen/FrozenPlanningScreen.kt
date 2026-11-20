package com.shifthud.frozen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun FrozenDayNavigation(date:LocalDate,today:LocalDate,change:(LocalDate)->Unit,week:()->Unit,nextShift:LocalDate?=null) {
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick={change(date.minusDays(1))},modifier=Modifier.width(48.dp).semantics {contentDescription="Previous day"}) {Text("‹")}
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d, uuuu",Locale.getDefault())),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            TextButton(onClick={change(date.plusDays(1))},modifier=Modifier.width(48.dp).semantics {contentDescription="Next day"}) {Text("›")}
        }
        FlowRow {
            if(date!=today) TextButton(onClick={change(today)}) {Text("Today")}
            if(nextShift!=null && date!=nextShift) TextButton(onClick={change(nextShift)}) {Text("Next Shift")}
            TextButton(onClick=week) {Text("Week View")}
        }
    }
}
@Composable internal fun FrozenFuturePreview(rows:List<FrozenDailyRow>,date:LocalDate,hasTemplate:Boolean) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item {Text("Frozen Workflow",style=MaterialTheme.typography.headlineMedium)
            Text("Read-only preview · carryovers are projected",style=MaterialTheme.typography.bodyMedium)
            if(!hasTemplate) Text("No recurring workflow defined. Projected carryovers only.")
            if(rows.isEmpty()) Text("No responsibilities or carryovers to preview.")
        }
        items(rows,key={it.task.id}) {row->
            Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                Text(row.task.title,style=MaterialTheme.typography.titleMedium)
                if(row.sources.any {it.date==date}) Text("Expected routine · preview",style=MaterialTheme.typography.bodySmall)
                row.sources.filter {it.date<date}.forEach {source->
                    Text("Projected carryover from ${source.date}",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.bodySmall)
                }
                if(row.task.description.isNotBlank()) Text(row.task.description,style=MaterialTheme.typography.bodySmall)
                row.sources.filter {it.notes.isNotBlank()}.forEach {Text("${it.date} · ${it.notes}",style=MaterialTheme.typography.bodyMedium)}
            }
            HorizontalDivider()
        }
    }
}
@Composable internal fun FrozenWeekView(days:List<FrozenPlanningDay>,scheduleLoaded:Boolean,open:(LocalDate)->Unit,previous:()->Unit,next:()->Unit,back:()->Unit) {
    val dateFormat=DateTimeFormatter.ofPattern("EEE, MMM d",Locale.getDefault())
    val context=androidx.compose.ui.platform.LocalContext.current
    val timeFormat=DateTimeFormatter.ofPattern(if(android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",Locale.getDefault())
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item {
            Text("Week View",style=MaterialTheme.typography.headlineMedium)
            Text("${days.first().date} – ${days.last().date} · Saturday–Friday",style=MaterialTheme.typography.bodySmall)
            Row {TextButton(onClick=previous,modifier=Modifier.weight(1f)) {Text("Previous week")};TextButton(onClick=next,modifier=Modifier.weight(1f)) {Text("Next week")}}
            TextButton(onClick=back) {Text("Daily checklist")}
        }
        items(days,key={it.date.toString()}) {day->
            Column(Modifier.fillMaxWidth().clickable(role=Role.Button,onClickLabel="Open ${day.date}") {open(day.date)}.padding(vertical=12.dp)) {
                Text(day.date.format(dateFormat),style=MaterialTheme.typography.titleMedium)
                Text(day.focus,style=MaterialTheme.typography.bodyMedium)
                if(!scheduleLoaded) Text("Schedule loading…",style=MaterialTheme.typography.bodySmall)
                else if(day.scheduledShifts.isEmpty()) Text("No saved shift",style=MaterialTheme.typography.bodySmall)
                else {
                    day.scheduledShifts.forEach {shift->Text("${shift.scheduledStart.format(timeFormat)} – ${shift.scheduledEnd.format(timeFormat)}${if(shift.end.toLocalDate()>shift.date) " (+1 day)" else ""}",style=MaterialTheme.typography.bodySmall)}
                    val minutes=day.scheduledSpan(ZoneId.systemDefault()).toMinutes()
                    Text("Scheduled span · ${minutes/60}h ${minutes%60}m (before lunch)",style=MaterialTheme.typography.bodySmall)
                }
                Text(if(day.preview) "Preview · ${day.rows.size} responsibilities" else if(day.rows.isEmpty()) "No saved active tasks" else "${day.completed} completed · ${day.outstanding} outstanding",style=MaterialTheme.typography.bodySmall)
                if(day.carryovers>0) Text("${day.carryovers} ${if(day.preview) "projected" else "active"} carryovers",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}
