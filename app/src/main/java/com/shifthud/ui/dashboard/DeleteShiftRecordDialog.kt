package com.shifthud.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shifthud.domain.model.WorkSession
import com.shifthud.ui.ShiftViewModel
import com.shifthud.ui.display
import java.time.*
import java.time.format.*

@Composable internal fun DeleteShiftRecordDialog(session: WorkSession, vm: ShiftViewModel, busy: Boolean, now: Instant, dismiss: () -> Unit, confirm: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val time = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a", locale)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    val start = session.clockIn.atZone(zone)
    val end = session.clockOut?.atZone(zone)
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Delete shift record?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(start.format(date))
            Text("${start.format(time)} – ${end?.format(time) ?: "In progress"}" + if (end != null && end.toLocalDate() != start.toLocalDate()) " · ${end.format(date)}" else "")
            Text("Paid: ${vm.engine.durations(session, 360, now).paid.display()}")
            Text("This removes this personal ShiftHUD work record and recalculates your hours and estimated gross pay. It does not affect your employer timecard.")
        }
    }, confirmButton = {
        TextButton(enabled = !busy && session.state == com.shifthud.domain.model.ShiftState.COMPLETE,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = confirm) { Text("DELETE") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("CANCEL") } })
}
