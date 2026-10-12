package com.shifthud.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shifthud.ui.display
import com.shifthud.domain.pay.money
import java.util.Locale

@Composable internal fun SchedulePlanningSummary(summary: SchedulePlanning, minutes: Int, locale: Locale) {
    val plan=summary.plan
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(when {
            summary.employerPaid==null -> "Publix scheduled: unknown — no saved shifts"
            summary.estimatedRows==0 -> "Publix scheduled: ${summary.employerPaid.display()}"
            else -> "Scheduled paid estimate: ${summary.employerPaid.display()} · ${summary.estimatedRows} shifts lack valid employer hours"
        })
        Text("Weekly projection · Planned lunch: ${minutes}m",style=MaterialTheme.typography.bodyMedium)
        Text("Projected paid: ${plan.projected.display()} · Target: ${plan.target.display()}",style=MaterialTheme.typography.titleMedium)
        Text(when { !plan.shortfall.isZero -> "Shortfall: ${plan.shortfall.display()}"; !plan.excess.isZero -> "Excess: ${plan.excess.display()}"; else -> "At weekly target" })
        Text("Actual paid: ${plan.worked.display()} · Projected remaining: ${plan.futurePaid.display()}")
        summary.projectedGross?.let { Text("Projected gross estimate: ${money(it,locale)} · Base pay") }
        Text("Saved records only · Confirm the week’s schedule is complete.",style=MaterialTheme.typography.bodySmall)
        if(summary.estimatedRows>0) Text("Scheduled estimate uses legacy/default lunch rules; weekly projection uses this week’s lunch plan.",style=MaterialTheme.typography.bodySmall)
        plan.issues.filterNot { it.startsWith("Saved shifts fall short") || it.startsWith("No remaining saved") }.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
    }
}
