package com.shifthud.ui.dashboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.shifthud.domain.calendar.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.ui.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable internal fun WorkCalendarSection(data: ShiftUiState, calendar: CalendarData, vm: ShiftViewModel, busy: Boolean,
    week: SessionBreakdown, showWeek: Boolean, dismissWeek: () -> Unit, openRecord: (Long) -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val context = androidx.compose.ui.platform.LocalContext.current
    val zone = ZoneId.systemDefault()
    val use24 = android.text.format.DateFormat.is24HourFormat(context)
    val estimator = remember(vm.engine) { PayEstimator(vm.engine) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<WorkSession?>(null) }
    val days = workCalendar(calendar.month, calendar.schedule, calendar.sessions, data.pay.rates, data.now, zone, estimator)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    fun stamp(value: Instant): String = value.atZone(zone).format(DateTimeFormatter.ofPattern(
        "MMM d, " + if (use24) "HH:mm:ss" else "h:mm:ss a", locale))
    fun gross(value: Long?) = value?.let { money(it, locale) } ?: "Unavailable"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WORK CALENDAR", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.calendarMonth(calendar.month.minusMonths(1)) }, modifier = Modifier.semantics { contentDescription = "Previous month" }) { Text("‹") }
            Text(calendar.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.calendarMonth(calendar.month.plusMonths(1)) }, modifier = Modifier.semantics { contentDescription = "Next month" }) { Text("›") }
        }
        if (calendar.error != null) Text(calendar.error, color = MaterialTheme.colorScheme.error)
        else if (!calendar.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        // 48dp minimum day targets; narrow windows can pan the grid rather than shrink taps.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gridWidth = maxOf(maxWidth, 336.dp)
            Column(Modifier.horizontalScroll(rememberScrollState()).width(gridWidth)) {
                Row {
                    WORK_WEEK_DAYS.forEach { day ->
                        Text(day.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                            Modifier.weight(1f).semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                    }
                }
                days.chunked(7).forEach { row ->
                    Row {
                        row.forEach { day ->
                            val inMonth = YearMonth.from(day.date) == calendar.month
                            val color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(Modifier.weight(1f).heightIn(min = 52.dp)
                                .background(if (day.isCurrentWeek) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .then(if (day.isToday) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)) else Modifier)
                                .clickable(enabled = calendar.loaded) { selectedDate = day.date.toString() }
                                .semantics(mergeDescendants = true) { contentDescription = day.date.format(dateFormat); stateDescription = day.stateDescription; role = Role.Button },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(day.date.dayOfMonth.toString(), color = color, style = MaterialTheme.typography.bodyMedium)
                                Text(day.indicator, color = if (day.hasActiveSession || day.hasCompletedSession) MaterialTheme.colorScheme.primary else color,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Text("● Worked   ▶ Active   ○ Scheduled   – No shift\nOutlined day: today · Shaded row: this work week", style = MaterialTheme.typography.bodySmall)
    }
    selectedDate?.let { selected ->
        val date = LocalDate.parse(selected)
        val day = days.firstOrNull { it.date == date }
        if (day != null) AlertDialog(onDismissRequest = { selectedDate = null }, title = { Text(date.format(dateFormat)) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (day.workSessions.isEmpty()) Text(if (day.scheduledShifts.isEmpty()) "No shift or recorded work session." else "Scheduled · No recorded work session yet.")
                if (day.workSessions.size > 1) {
                    Text("TOTAL FOR DAY", style = MaterialTheme.typography.titleSmall)
                    Text("Paid: ${recordDuration(day.paidDuration)}\nEst. gross: ${gross(day.estimatedGross)}")
                }
                day.breakdown.contributions.forEachIndexed { index, contribution ->
                    val session = contribution.session
                    key(session.id) {
                        val durations = vm.engine.durations(session, data.threshold, data.now)
                        Text("SESSION ${index + 1} · " + if (session.state == ShiftState.COMPLETE) "WORKED" else "ACTIVE", style = MaterialTheme.typography.titleSmall)
                        Text("${stamp(session.clockIn)} – ${session.clockOut?.let(::stamp) ?: "In progress"}")
                        Text(session.lunchStart?.let { "Lunch: ${stamp(it)} – ${session.lunchEnd?.let(::stamp) ?: "In progress"}" } ?: "Lunch: Not recorded")
                        Text("Paid: ${recordDuration(contribution.paid)}\nStore: ${recordDuration(durations.store)}\nLunch: ${recordDuration(durations.lunch)}\nEst. gross: ${gross(contribution.grossCents)}")
                        if (session.manuallyEntered) Text("Manually added", style = MaterialTheme.typography.labelMedium)
                        if (session.lunchEndAutomatic) Text("Lunch end inferred automatically", style = MaterialTheme.typography.labelMedium)
                        TextButton(enabled = !busy, onClick = { openRecord(session.id) }) { Text("EDIT TIME") }
                        if (session.manuallyEntered && session.state == ShiftState.COMPLETE) TextButton(enabled = !busy, onClick = { deleting = session }) { Text("DELETE SHIFT") }
                        HorizontalDivider()
                    }
                }
                day.scheduledShifts.forEach { schedule ->
                    Text("Scheduled: ${schedule.start.atZone(zone).toInstant().let(::stamp)} – ${schedule.end.atZone(zone).toInstant().let(::stamp)}")
                }
            }
        }, confirmButton = { TextButton(onClick = { selectedDate = null }) { Text("Close") } })
    }
    if (showWeek) {
        val range = workWeekFor(data.now.atZone(zone).toLocalDate())
        AlertDialog(onDismissRequest = dismissWeek, title = { Text("THIS WEEK · ${range.start.format(DateTimeFormatter.ofPattern("MMM d", locale))}–${range.endInclusive.format(DateTimeFormatter.ofPattern("MMM d", locale))}") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paid: ${recordDuration(week.paid)}\nEst. gross: ${gross(week.grossCents)}", style = MaterialTheme.typography.titleMedium)
                if (week.contributions.isEmpty()) Text("No recorded work this week.")
                week.contributions.forEachIndexed { index, row ->
                    TextButton(enabled = !busy, onClick = { openRecord(row.session.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("${row.session.clockIn.atZone(zone).format(DateTimeFormatter.ofPattern("EEE MMM d", locale))} · Session ${index + 1}")
                            Text("${stamp(row.session.clockIn)} – ${row.session.clockOut?.let(::stamp) ?: "In progress"}")
                            Text("Paid: ${recordDuration(row.paid)} · ${gross(row.grossCents)}")
                            Text(if (row.session.manuallyEntered) "Manually added · Edit time" else "Recorded · Edit time", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = dismissWeek) { Text("Close") } })
    }
    deleting?.let { session ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete this work session?") },
            text = { Text("This removes its hours and estimated gross from ShiftHUD. This does not affect employer records.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { vm.deleteHistorical(session) { deleting = null } }) { Text("Delete") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancel") } })
    }
}
