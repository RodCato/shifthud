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
import com.shifthud.domain.planner.*
import com.shifthud.domain.pay.workWeekFor
import com.shifthud.domain.weekly.WeeklyTargetSettings
import com.shifthud.ui.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun LunchPlanControl(vm:ShiftViewModel,busy:Boolean, initialWeek: LocalDate? = null, label: String = "Edit Lunch Plan") {
    var open by androidx.compose.runtime.saveable.rememberSaveable(initialWeek) { mutableStateOf(false) }
    OutlinedButton(onClick={open=true},enabled=!busy) { Text(label) }
    if(open) LunchPlanDialog(vm,busy,initialWeek) { open=false }
}
@Composable internal fun LunchPlanDialog(vm:ShiftViewModel,busy:Boolean,initialWeek:LocalDate?,close:()->Unit) {
    val context=LocalContext.current
    val app=context.applicationContext as ShiftHudApplication
    val settings by app.preferences.weeklyPlannerSettings.collectAsState(initial=null)
    val target by app.preferences.weeklyTargetSettings.collectAsState(initial=WeeklyTargetSettings())
    val shifts by app.repository.schedule.collectAsState(initial=null)
    val records by app.repository.sessions.collectAsState(initial=null)
    val data by vm.state.collectAsState()
    val saved=settings ?: return
    if(shifts==null || records==null) return
    val zone=ZoneId.systemDefault()
    val currentWeek=workWeekFor(data.now.atZone(zone).toLocalDate()).start
    var weekText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf((initialWeek ?: currentWeek).toString()) }
    val week=LocalDate.parse(weekText)
    var minutesText by androidx.compose.runtime.saveable.rememberSaveable(weekText) { mutableStateOf(saved.weeklyMinutes(week).toString()) }
    val minutes=minutesText.toIntOrNull()?.takeIf { it in maxOf(31,saved.minimumMinutes)..240 }
    val locale=LocalConfiguration.current.locales[0]
    val fmt=DateTimeFormatter.ofPattern(if(android.text.format.DateFormat.is24HourFormat(context)) "EEEE · HH:mm" else "EEEE · h:mm a",locale)
    // A future week's preview starts at Saturday midnight; current week uses live punches.
    val previewNow=maxOf(data.now,week.atStartOfDay(zone).toInstant())
    val plan=minutes?.let { clockOutPlan(records!!,shifts!!,target.target,saved.withWeek(week,it),previewNow,zone,week) }
    Dialog(onDismissRequest={if(!busy)close()}) {
        Surface(shape=MaterialTheme.shapes.large) { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Weekly Lunch Plan",style=MaterialTheme.typography.titleLarge)
            Text("Saturday–Friday: $week – ${week.plusDays(6)}")
            Row(Modifier.fillMaxWidth()) {
                TextButton(modifier=Modifier.weight(1f),enabled=!busy,onClick={weekText=week.minusWeeks(1).toString()}) { Text("Previous week") }
                TextButton(modifier=Modifier.weight(1f),enabled=!busy,onClick={weekText=week.plusWeeks(1).toString()}) { Text("Next week") }
            }
            OutlinedTextField(minutesText,{minutesText=it},label={Text("Planned lunch minutes")},suffix={Text("min")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Text("Minimum ${maxOf(31,saved.minimumMinutes)} minutes · Maximum 240")
            if(minutes==null) Text("Enter whole minutes within the permitted range.",color=MaterialTheme.colorScheme.error)
            Text("Saved plan: ${saved.weeklyMinutes(week)} minutes${if(week !in saved.weeklyLunches) " · Inherited default" else ""}")
            HorizontalDivider()
            Text("UNSAVED PREVIEW",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
            val summary=com.shifthud.ui.schedule.schedulePlanning(workWeekFor(week),shifts!!,records!!,saved,target.target,previewNow,zone)
            Text(summary.scheduledLabel(),style=MaterialTheme.typography.bodySmall)
            com.shifthud.ui.components.ExpandableSection("Scheduled Hours Details","Sources and assumptions") {
                Text(summary.metadataLabel())
                Text("Missing employer hours use legacy/default lunch estimates. Personal projections use the selected weekly lunch plan.")
                Text(PLANNING_ROUNDING_NOTE)
            }
            if(plan!=null) {
                Text("Actual worked: ${plan.worked.display()}")
                Text("Projected remaining: ${plan.futurePaid.display()}")
                Text("Projected weekly total: ${plan.projected.display()}",style=MaterialTheme.typography.titleMedium)
                Text("Target: ${target.target.display()}\nRemaining paid time to target: ${plan.remaining.countdownLabel()}")
                if(!plan.shortfall.isZero) Text("Shortfall: ${plan.shortfall.countdownLabel()} of additional paid work needed. Extra work is not assumed.")
                if(!plan.excess.isZero) Text("Projected excess: ${plan.excess.excessLabel()}")
                Text("Recommended final-day clock-out: ${plan.clockOut?.atZone(zone)?.format(fmt) ?: "Unavailable — review schedule"}")
                plan.issues.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
            }
            Text("Applies to this week's projections. Legacy individual lunch values remain stored for reference. Employer schedules, actual punches and payroll stay unchanged. Confirm manager approval for early departure.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy && minutes!=null,onClick={vm.weeklyLunch(week,minutes!!,close)}) { Text("SAVE WEEKLY PLAN") }
            TextButton(onClick=close,enabled=!busy) { Text("CANCEL") }
        } }
    }
}
