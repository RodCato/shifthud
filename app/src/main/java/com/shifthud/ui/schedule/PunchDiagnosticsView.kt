package com.shifthud.ui.schedule

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shifthud.ui.components.ExpandableSection
import com.shifthud.ui.time.DECIMAL_TIME_NOTE

@Composable internal fun PunchDiagnosticsView(details: PunchDiagnostics?) {
    details ?: return
    ExpandableSection("Punch Diagnostics","Recorded seconds and duration breakdown") {
        Text(details.scheduled,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        details.sessions.forEach { lines ->
            lines.forEach { Text(it,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
        }
        if(details.sessions.size>1) {
            Text("Linked sessions total",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleSmall)
            details.totals.forEach { Text(it,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium) }
        }
        Text(details.precisionNote,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        Text(DECIMAL_TIME_NOTE,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
    }
}
