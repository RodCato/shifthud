package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable internal fun FloatCounter(counts:FloatCounts?,date:LocalDate,today:LocalDate,adjust:(Int)->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
        Text(if(date==today) "FLOATS COMPLETED TODAY" else "FLOATS · $date",style=MaterialTheme.typography.labelLarge)
        Text(counts?.get(date)?.toString() ?: "—",style=MaterialTheme.typography.headlineMedium,
            modifier=Modifier.semantics {contentDescription="Floats completed ${if(date==today) "today" else date}: ${counts?.get(date) ?: "loading"}"})
        if(date==today) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={adjust(-1)},enabled=counts!=null,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Subtract one float"}) {Text("−1")}
            Button(onClick={adjust(1)},enabled=counts!=null,modifier=Modifier.heightIn(min=48.dp)) {Text("+1 FLOAT")}
        }
        counts?.let {Text("Week: ${it.week(date)} · Month: ${it.month(date)}",style=MaterialTheme.typography.bodySmall)}
    }
}
