package com.shifthud.ui.dashboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shifthud.domain.calendar.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.ui.*
import java.time.*
import java.time.format.*

/** Shared by Dashboard calendar and Analytics; session identity stays independent of Dashboard selection. */
@Composable internal fun WorkDayDetailsDialog(day: WorkCalendarDay, data: ShiftUiState, vm: ShiftViewModel, busy: Boolean, dismiss: () -> Unit, openRecord: (Long) -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val context = androidx.compose.ui.platform.LocalContext.current
    val zone = ZoneId.systemDefault()
    val use24 = android.text.format.DateFormat.is24HourFormat(context)
    val date = day.date
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    fun stamp(value: Instant): String = value.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, " + if (use24) "HH:mm" else "h:mm a", locale))
    fun gross(value: Long?) = value?.let { money(it, locale) } ?: "Unavailable"
    var deleting by remember { mutableStateOf<WorkSession?>(null) }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(date.format(dateFormat)) }, text = {
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
                        if (session.state == ShiftState.COMPLETE) TextButton(enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { deleting = session }) { Text("DELETE SHIFT RECORD") }
                        HorizontalDivider()
                    }
                }
                day.scheduledShifts.forEach { schedule ->
                    Text("Scheduled: ${schedule.start.atZone(zone).toInstant().let(::stamp)} – ${schedule.end.atZone(zone).toInstant().let(::stamp)}")
                }
            }
        }, confirmButton = { TextButton(onClick = { dismiss() }) { Text("Close") } })
    deleting?.let { session ->
        DeleteShiftRecordDialog(session, vm, busy, data.now, { deleting = null }) {
            vm.deleteHistorical(session) { deleting = null }
        }
    }
}
