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
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(summary.scheduledLabel())
        Text("Weekly projection · Planned lunch: ${minutes}m",style=MaterialTheme.typography.bodyMedium)
        Text("Projected paid: ${plan.projected.display()} · Target: ${plan.target.display()}",style=MaterialTheme.typography.titleMedium)
        Text(when { !plan.shortfall.isZero -> "Shortfall: ${plan.shortfall.countdownLabel()}"; !plan.excess.isZero -> "Excess: ${plan.excess.excessLabel()}"; else -> "At weekly target" })
        Text("Actual paid: ${plan.worked.display()} · Projected remaining: ${plan.futurePaid.display()}")
        summary.projectedGross?.let { Text("Projected gross estimate: ${money(it,locale)} · Base pay") }
        Text("Saved records only · Confirm the week’s schedule is complete.",style=MaterialTheme.typography.bodySmall)
        com.shifthud.ui.components.ExpandableSection("Schedule Planning Details","Sources and assumptions") {
            Text(summary.metadataLabel())
            if(summary.estimatedRows>0) Text("Scheduled estimate uses legacy/default lunch rules; weekly projection uses this week’s lunch plan.",style=MaterialTheme.typography.bodySmall)
            plan.issues.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
            Text(PLANNING_ROUNDING_NOTE,style=MaterialTheme.typography.bodySmall)
        }
    }
}
