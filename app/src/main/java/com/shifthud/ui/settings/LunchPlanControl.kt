package com.shifthud.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.ScheduledShift
import com.shifthud.domain.planner.*
import com.shifthud.domain.pay.workWeekFor
import com.shifthud.domain.weekly.WeeklyTargetSettings
import com.shifthud.ui.*
import java.time.*
import java.time.format.DateTimeFormatter

private data class LunchReview(val shifts:List<ScheduledShift>,val minutes:Int,val replace:Boolean,val week:LocalDate,val summary:String)
@Composable fun LunchPlanControl(vm:ShiftViewModel,busy:Boolean) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick={open=true},enabled=!busy) { Text("Adjust lunch plan") }
    if(open) LunchPlanDialog(vm,busy) { open=false }
}
@Composable private fun LunchPlanDialog(vm:ShiftViewModel,busy:Boolean,close:()->Unit) {
    val context=LocalContext.current
    val app=context.applicationContext as ShiftHudApplication
    val settings by app.preferences.plannerSettings.collectAsState(initial=null)
    val target by app.preferences.weeklyTargetSettings.collectAsState(initial=WeeklyTargetSettings())
    val shifts by app.repository.schedule.collectAsState(initial=null)
    val records by app.repository.sessions.collectAsState(initial=null)
    val data by vm.state.collectAsState()
    val saved=settings
    if(saved==null || shifts==null || records==null) return
    var minutesText by remember { mutableStateOf(saved.plannedMinutes.toString()) }
    var replace by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<LunchReview?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val minutes=minutesText.toIntOrNull()?.takeIf { it in saved.minimumMinutes..240 }
    val zone=ZoneId.systemDefault()
    val week=workWeekFor(data.now.atZone(zone).toLocalDate())
    val candidates=lunchPlanCandidates(shifts!!,records!!,data.now,zone,replace)
    val locale=LocalConfiguration.current.locales[0]
    val fmt=DateTimeFormatter.ofPattern(if(android.text.format.DateFormat.is24HourFormat(context)) "EEEE · HH:mm:ss" else "EEEE · h:mm:ss a",locale)
    val plan=minutes?.let { clockOutPlan(records!!,hypotheticalLunchPlan(shifts!!,candidates,it),target.target,saved,data.now,zone) }
    val summary=plan?.let { "Projected weekly hours: ${it.projected.display()}\nProjected excess: ${it.excess.display()}\nRecommended final-day clock-out: ${it.clockOut?.atZone(zone)?.format(fmt) ?: if(it.reached) "Weekly target reached" else "Unavailable — review schedule"}" }.orEmpty()
    Dialog(onDismissRequest={if(!busy)close()}) {
        Surface(shape=MaterialTheme.shapes.large) { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Weekly Hours Planner",style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(minutesText,{minutesText=it;result=null},label={Text("Default planned lunch duration")},suffix={Text("min")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Text("Minimum ${saved.minimumMinutes} minutes · Maximum 240")
            if(minutes==null) Text("Enter whole minutes within the permitted range.",color=MaterialTheme.colorScheme.error)
            Text("Saved default: ${saved.plannedMinutes} minutes. New shifts inherit it; existing plans keep their saved values.",style=MaterialTheme.typography.bodySmall)
            Text("HYPOTHETICAL PREVIEW · Not saved",style=MaterialTheme.typography.titleMedium)
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(replace,{replace=it;result=null},enabled=!busy)
                Text("Replace individual overrides too")
            }
            Text(if(replace) "Confirmation will replace eligible individual overrides with the selected global plan." else "Individual overrides are preserved. Select replacement explicitly to include them.")
            Text("Saturday–Friday: ${week.start} – ${week.endInclusive}\n${candidates.size} affected future, unworked shifts")
            minutes?.let { Text("Selected lunch: $it minutes") }
            if(plan!=null) { Text(summary);plan.issues.forEach { Text(it,style=MaterialTheme.typography.bodySmall) } }
            Text("Uses saved schedules and actual punches. No active or worked shift is changed. Confirm schedule completeness and manager approval to leave early.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy && minutes!=null,onClick={vm.planner(saved.copy(plannedMinutes=minutes!!))}) { Text("SAVE DEFAULT FOR NEW SHIFTS") }
            OutlinedButton(enabled=!busy && minutes!=null && candidates.isNotEmpty(),onClick={review=LunchReview(candidates,minutes!!,replace,week.start,summary)}) { Text("Apply planned lunch to remaining shifts this week") }
            result?.let { Text(it) }
            TextButton(onClick=close,enabled=!busy) { Text("CLOSE") }
        } }
    }
    review?.let { proposed -> AlertDialog(onDismissRequest={if(!busy)review=null},title={Text("Apply lunch plan?")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${proposed.minutes} minutes · ${proposed.shifts.size} shifts\nSaturday–Friday: ${proposed.week} – ${proposed.week.plusDays(6)}")
            Text(proposed.summary)
            Text(if(proposed.replace) "Existing individual overrides in these shifts will be replaced." else "Individual overrides will be preserved.")
            Text("Only future, unworked shifts are updated. IDs, times, notes, actual punches and payroll remain unchanged. The saved global default is a separate setting.")
        }
    },confirmButton={TextButton(enabled=!busy,onClick={vm.applyLunch(proposed.shifts,proposed.minutes,proposed.replace,proposed.week) { review=null;result="Updated ${proposed.shifts.size} shift lunch plans." }}) { Text("CONFIRM APPLY") }},dismissButton={TextButton(enabled=!busy,onClick={review=null}) { Text("CANCEL") }}) }
}
