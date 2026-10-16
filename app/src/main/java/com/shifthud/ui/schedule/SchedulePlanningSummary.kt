package com.shifthud.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shifthud.ui.*
import com.shifthud.domain.pay.money
import java.util.Locale

@Composable internal fun SchedulePlanningSummary(summary: SchedulePlanning, minutes: Int) {
    val plan=summary.plan
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().testTag("schedule-employer").semantics(mergeDescendants=true) {}) {
            Text(if(summary.estimatedRows>0) "Scheduled paid estimate" else "Scheduled paid",modifier=Modifier.fillMaxWidth(),
                style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summary.employerPaid?.display() ?: "Unknown — no saved shifts",modifier=Modifier.fillMaxWidth(),
                style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        }
        Text(when {
            summary.employerPaid==null -> "No employer hours available"
            summary.estimatedRows>0 -> "Estimate · Employer hours for ${summary.shiftCount-summary.estimatedRows} of ${summary.shiftCount} shifts"
            else -> "Employer-reported schedule hours"
        },modifier=Modifier.fillMaxWidth().testTag("schedule-hours-source"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Projected paid: ${plan.projected.display()}",modifier=Modifier.fillMaxWidth().testTag("schedule-projected"),
            style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Text("Personal projection · Planned lunch: ${minutes}m per shift",modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        Text("Target: ${plan.target.display()} · " + when {
            !plan.shortfall.isZero -> "Shortfall: ${plan.shortfall.countdownLabel()}"
            !plan.excess.isZero -> "Excess: ${plan.excess.excessLabel()}"
            else -> "At weekly target"
        },modifier=Modifier.fillMaxWidth().testTag("schedule-target"),style=MaterialTheme.typography.bodyMedium,
            color=if(!plan.excess.isZero) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        LinearProgressIndicator(progress={ (plan.projected.toMillis().toDouble()/plan.target.toMillis()).coerceIn(0.0,1.0).toFloat() },
            modifier=Modifier.fillMaxWidth().testTag("schedule-progress").semantics { contentDescription="Projected paid progress toward weekly target" })
        Text("Saved records only · Confirm the week’s schedule is complete.",modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun SchedulePlanningDetails(summary: SchedulePlanning, locale: Locale) {
    val plan=summary.plan
    com.shifthud.ui.components.ExpandableSection("Schedule Planning Details","Sources and assumptions") {
        Text(summary.metadataLabel())
        if(summary.estimatedRows>0) Text("Scheduled estimate uses legacy/default lunch rules; personal projection uses this week’s lunch plan.",style=MaterialTheme.typography.bodySmall)
        Text("Actual paid: ${plan.worked.display()} · Projected remaining: ${plan.futurePaid.display()}",style=MaterialTheme.typography.bodyMedium)
        summary.projectedGross?.let { Text("Projected gross estimate: ${money(it,locale)} · Base pay",style=MaterialTheme.typography.bodyMedium) }
        plan.issues.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
        Text(PLANNING_ROUNDING_NOTE,style=MaterialTheme.typography.bodySmall)
    }
}
