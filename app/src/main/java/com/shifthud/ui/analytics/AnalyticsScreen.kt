package com.shifthud.ui.analytics

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.domain.analytics.*
import com.shifthud.domain.calendar.recordDuration
import com.shifthud.domain.pay.money
import com.shifthud.ui.*
import com.shifthud.ui.dashboard.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun AnalyticsScreen(analytics: AnalyticsViewModel, vm: ShiftViewModel, data: ShiftUiState, busy: Boolean, back: () -> Unit, openPaychecks: (LocalDate) -> Unit = {}) {
    val state by analytics.state.collectAsStateWithLifecycle()
    val weekly by analytics.weekly.collectAsStateWithLifecycle()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var recordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var previous by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.period) { selected = null; listState.scrollToItem(0) }
    val result = state.result
    val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
    fun gross(cents: Long?) = cents?.let { money(it, locale) } ?: "Unavailable — missing rate history"
    LazyColumn(Modifier.fillMaxSize(), state=listState, contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick=back) { Text("‹ DASHBOARD") }
            Text("Work analytics", style=MaterialTheme.typography.headlineMedium)
            Text("Recorded work · Estimated base gross", style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick={openPaychecks(state.period.start)}) { Text("PAYCHECKS") }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(selected=weekly,onClick={analytics.mode(true)},label={Text("Week")})
                FilterChip(selected=!weekly,onClick={analytics.mode(false)},label={Text("Month")})
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={analytics.move(-1)},modifier=Modifier.semantics { contentDescription=if(weekly) "Previous week" else "Previous month" }) { Text("‹") }
                Text(if(weekly) "${state.period.start.format(dateFormat)} –\n${state.period.endExclusive.minusDays(1).format(dateFormat)}" else state.period.start.format(DateTimeFormatter.ofPattern("LLLL yyyy",locale)), Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={analytics.move(1)},modifier=Modifier.semantics { contentDescription=if(weekly) "Next week" else "Next month" }) { Text("›") }
            }
            TextButton(onClick=analytics::current) { Text(if(weekly) "CURRENT WEEK" else "CURRENT MONTH") }
            if(weekly) Text("Saturday–Friday",style=MaterialTheme.typography.labelMedium)
        }
        if (state.error != null) item { Text(state.error!!,color=MaterialTheme.colorScheme.error);Button(onClick=analytics::retry){Text("RETRY")} }
        else if(result == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else {
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Paid · ${recordDuration(result.paid)}",style=MaterialTheme.typography.headlineSmall)
                    Text("Est. gross · ${gross(result.grossCents)}",style=MaterialTheme.typography.titleLarge)
                    Text("${result.workedDays} worked days · ${result.completedSessions} completed sessions")
                    Text("Average / worked day · ${recordDuration(result.averagePaid)}")
                    if(result.activeSessions>0) {
                        Text("IN PROGRESS · ${result.activeSessions} session(s)",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                        Text("Completed: ${recordDuration(result.completedPaid)}\nAccrued in progress: ${recordDuration(result.accruedPaid)} · included above")
                    }
                    if(result.breakdown.contributions.isEmpty()) Text("No recorded work in this period. Scheduled shifts are not worked hours.")
                } }
            }
            if(weekly) item {
                Card { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Current weekly target · ${recordDuration(result.target.target)}",style=MaterialTheme.typography.titleMedium)
                    Text("${(result.targetProgress*100).toInt()}% · ${recordDuration(result.target.target.minus(result.paid).coerceAtLeast(Duration.ZERO))} remaining")
                    LinearProgressIndicator(progress={result.targetProgress.toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                    if(!result.target.enabled) Text("Target tracking is disabled in Settings.",style=MaterialTheme.typography.bodySmall)
                    Text("Uses your current target preference for comparison; historical targets are not stored.",style=MaterialTheme.typography.bodySmall)
                } }
            }
            item { PaidChart(result) }
            item { Text("DAILY RECORDS",style=MaterialTheme.typography.titleMedium) }
            items(result.days,key={it.date.toEpochDay()}) { day ->
                OutlinedCard(onClick={selected=day.date.toString()},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(day.date.format(DateTimeFormatter.ofPattern("EEE, MMM d",locale)),style=MaterialTheme.typography.titleMedium)
                        Text("${recordDuration(day.paidDuration)} paid · ${gross(day.estimatedGross)}")
                        Text(if(day.workSessions.isEmpty()) if(day.scheduledShifts.isEmpty()) "No recorded work" else "Scheduled only · No recorded work" else "${day.workSessions.size} session(s)${if(day.hasActiveSession) " · IN PROGRESS" else " · Completed"}",style=MaterialTheme.typography.bodySmall)
                        Text("VIEW DAY DETAILS",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { OutlinedButton(onClick={previous=true},enabled=!busy) { Text("ADD PREVIOUS SHIFT") } }
            item { Text("All work is attributed to the local clock-in date, including overnight shifts. Worked days have positive recorded paid time. Durations display whole minutes; gross uses precise recorded durations and each session’s effective historical rate, rounded to cents per session. Daily rows sum to the period’s gross. Displayed minutes may not reproduce the exact gross or sum due to truncation.\n\nBase-pay estimates only: no taxes, deductions, net pay, or overtime premiums.",style=MaterialTheme.typography.bodySmall) }
        }
    }
    selected?.let { date -> result?.days?.firstOrNull { it.date.toString()==date }?.let { day ->
        WorkDayDetailsDialog(day,data.copy(now=result.asOf),vm,busy,{selected=null}) { recordId=it }
    } }
    recordId?.let { id ->
        val record by remember(id) { vm.record(id) }.collectAsStateWithLifecycle(initialValue=null)
        record?.let { TimeRecordDialog(it,vm,busy,data.now) { recordId=null } }
    }
    if(previous) PreviousShiftDialog(data,vm,busy,{previous=false}) { recordId=it;previous=false }
}

@Composable private fun PaidChart(result: WorkAnalytics) {
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val primary=MaterialTheme.colorScheme.primary
    val baseline=MaterialTheme.colorScheme.outlineVariant
    val maxMillis=maxOf(Duration.ofHours(8).toMillis(),result.days.maxOf { it.paidDuration.toMillis() }).toFloat()
    val description=result.days.joinToString("; ") { "${it.date}: ${recordDuration(it.paidDuration)} paid${if(it.hasActiveSession) ", in progress" else ""}" }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("PAID HOURS BY DAY",style=MaterialTheme.typography.titleMedium)
        Text("Scale: 0–${recordDuration(Duration.ofMillis(maxMillis.toLong()))} · includes in-progress work",style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription=description }) {
            val slot=size.width/result.days.size
            drawLine(baseline,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
            result.days.forEachIndexed { index, day ->
                val height=size.height * (day.paidDuration.toMillis()/maxMillis)
                drawRect(primary,Offset(index*slot+slot*.15f,size.height-height),Size(slot*.7f,height))
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            if(result.period.weekly) result.days.forEach { Text(it.date.format(DateTimeFormatter.ofPattern("EEE",locale)),Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelSmall) }
            else { Text("1",style=MaterialTheme.typography.labelSmall);Text(result.days.last().date.dayOfMonth.toString(),style=MaterialTheme.typography.labelSmall) }
        }
        Text("Daily values and session details below.",style=MaterialTheme.typography.bodySmall)
    }
}
