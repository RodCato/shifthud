package com.shifthud.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.weekly.*
import com.shifthud.domain.planner.*
import com.shifthud.domain.model.ShiftState
import com.shifthud.ui.*
import com.shifthud.ui.components.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun WeeklyTargetSection(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as ShiftHudApplication
    val settings by app.preferences.weeklyTargetSettings.collectAsState(initial = WeeklyTargetSettings())
    val planning by app.preferences.weeklyPlannerSettings.collectAsState(initial = PlannerSettings())
    val locale = LocalConfiguration.current.locales[0]
    if (!settings.enabled) return
    if (!data.pay.loaded) { Text("Weekly target: ${data.pay.error ?: "Loading records…"}"); return }
    val zone = ZoneId.systemDefault()
    val records = (listOfNotNull(data.session) + data.pay.sessions).distinctBy { it.id }
    val plan = clockOutPlan(records,data.schedule,settings.target,planning,data.now,zone)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("Weekly hours")
            Metric("Actual worked hours",plan.worked.display(),"weekly-actual",primary=true,supporting="of ${settings.target.display()} target")
            LinearProgressIndicator(progress = { (plan.worked.toMillis().toDouble()/settings.target.toMillis()).coerceIn(0.0,1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(if(plan.reached) "Weekly target reached." else "Remaining: ${plan.remaining.countdownLabel()}")
            HorizontalDivider()
            Metric("Projected weekly total",plan.projected.display(),"weekly-projected")
            if(!plan.excess.isZero) Text("Projected excess: ${plan.excess.excessLabel()}",color=MaterialTheme.colorScheme.error)
            if(!plan.shortfall.isZero) Text("Projected shortfall: ${plan.shortfall.countdownLabel()}")
            plan.clockOut?.let {
                val seconds = if(it.atZone(zone).second == 0 && it.nano == 0) "" else ":ss"
                val pattern = if(android.text.format.DateFormat.is24HourFormat(context)) "EEEE · HH:mm$seconds" else "EEEE · h:mm$seconds a"
                Metric("Estimated final-day clock-out:",it.atZone(zone).format(DateTimeFormatter.ofPattern(pattern,locale)),"weekly-clock-out")
            }
            if(data.session?.state == ShiftState.ON_LUNCH) Text("Paid time paused during lunch. Estimate updates when lunch ends.")

            com.shifthud.ui.components.ExpandableSection("View Planning Details","Sources and assumptions") {
                val week=com.shifthud.domain.pay.workWeekFor(data.now.atZone(zone).toLocalDate())
                val summary=com.shifthud.ui.schedule.schedulePlanning(week,data.schedule,records,planning,settings.target,data.now,zone)
                Text(summary.scheduledLabel())
                Text(summary.metadataLabel())
                Text("Planned lunch: ${planning.weeklyMinutes(week.start)}m · Edit in Schedule")
                plan.clockOut?.let {
                    Text(if(plan.lunchRemaining.isZero) "No further lunch assumed in the final shift." else "Includes ${plan.lunchRemaining.display()} of future unpaid lunch in the final shift.")
                }
                plan.issues.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                Text("Confirm that the week’s saved schedule is complete.",style=MaterialTheme.typography.bodySmall)
                Text("Completed: ${plan.completed.display()}\nActive paid work: ${plan.activePaid.display()}\nFuture scheduled paid work: ${plan.futurePaid.display()}")
                Text(PLANNING_ROUNDING_NOTE,style=MaterialTheme.typography.bodySmall)
                Text("Manager approval is required for early departure.",style=MaterialTheme.typography.bodySmall)
                Text("Planning estimate only. Employer timecard is authoritative. No punches or schedules are changed.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
