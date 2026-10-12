package com.shifthud.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shifthud.ui.*
import com.shifthud.domain.pay.money
import java.util.Locale

@Composable internal fun SchedulePlanningSummary(summary: SchedulePlanning, minutes: Int, locale: Locale) {
    val plan=summary.plan
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        com.shifthud.ui.components.Metric(
            if(summary.estimatedRows>0) "Estimated employer paid hours" else "Employer-reported paid hours",
            summary.employerPaid?.display() ?: "Unknown — no saved shifts","schedule-employer")
        HorizontalDivider()
        com.shifthud.ui.components.SectionHeading("Personal projection")
        com.shifthud.ui.components.Metric("Projected paid hours",plan.projected.display(),"schedule-projected",
            supporting="Target: ${plan.target.display()}")
        Text(when { !plan.shortfall.isZero -> "Projected shortfall: ${plan.shortfall.countdownLabel()}"; !plan.excess.isZero -> "Projected excess: ${plan.excess.excessLabel()}"; else -> "At weekly target" })
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("Actual paid: ${plan.worked.display()} · Projected remaining: ${plan.futurePaid.display()}",style=MaterialTheme.typography.bodyMedium)
            Text("Planned lunch: ${minutes}m",style=MaterialTheme.typography.bodyMedium)
            summary.projectedGross?.let { Text("Projected gross estimate: ${money(it,locale)} · Base pay",style=MaterialTheme.typography.bodyMedium) }
        }
        com.shifthud.ui.components.ExpandableSection("Schedule Planning Details","Sources and assumptions") {
            Text(summary.metadataLabel())
            Text("Saved records only · Confirm the week’s schedule is complete.",style=MaterialTheme.typography.bodySmall)
            if(summary.estimatedRows>0) Text("Scheduled estimate uses legacy/default lunch rules; weekly projection uses this week’s lunch plan.",style=MaterialTheme.typography.bodySmall)
            plan.issues.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
            Text(PLANNING_ROUNDING_NOTE,style=MaterialTheme.typography.bodySmall)
        }
    }
}
