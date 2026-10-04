package com.shifthud.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shifthud.domain.model.*
import com.shifthud.ui.*
import java.time.*

@Composable fun DashboardScreen(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean) {
    val today = data.now.atZone(ZoneId.systemDefault()).toLocalDate()
    val scheduled = data.schedule.firstOrNull { it.date == today }
    val session = data.session?.takeIf { it.state != ShiftState.COMPLETE || it.clockOut?.atZone(ZoneId.systemDefault())?.toLocalDate() == today }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PUBLIX SHIFT", style = MaterialTheme.typography.headlineMedium)
        Text("ShiftHUD • Personal tracker", style = MaterialTheme.typography.labelLarge)
        if (!data.loaded) { CircularProgressIndicator(); return@Column }
        if (session == null) {
            Text(if (scheduled != null) "TODAY" else "OFF TODAY", style = MaterialTheme.typography.titleLarge)
            scheduled?.let { Text(it.timeLabel(), style = MaterialTheme.typography.headlineSmall); it.notes?.let { note -> Text(note) } }
            if (scheduled == null) {
                val next = data.schedule.firstOrNull { it.date > today }
                Text(next?.let { "Next shift: ${it.date.format(dateFormat)} ${it.timeLabel()}" } ?: "No upcoming shifts. Add your schedule in Schedule.")
            }
            Button(onClick = vm::clockIn, enabled = !busy) { Text(if (scheduled == null) "CLOCK IN — UNSCHEDULED" else "CLOCK IN") }
        } else {
            val durations = vm.engine.durations(session, data.threshold, data.now)
            val linked = data.schedule.firstOrNull { it.id == session.scheduledShiftId }
            when (session.state) {
                ShiftState.WORKING -> {
                    Text("WORKING — ${durations.activeWork.display()}", style = MaterialTheme.typography.headlineSmall)
                    Text(if (durations.lunchRemaining.isNegative || durations.lunchRemaining.isZero) "Lunch threshold reached" else "Lunch due in ${durations.lunchRemaining.display()}")
                    if (session.lunchEnd != null) Text("Lunch taken: ${durations.lunch.display()}")
                    if (session.lunchStart == null) Button(onClick = { vm.startLunch(session) }, enabled = !busy) { Text("START LUNCH") }
                    Button(onClick = { vm.clockOut(session) }, enabled = !busy) { Text("CLOCK OUT") }
                }
                ShiftState.ON_LUNCH -> {
                    Text("ON LUNCH — ${durations.lunch.display()}", style = MaterialTheme.typography.headlineSmall)
                    Text("Paid: ${durations.paid.display()}")
                    Button(onClick = { vm.endLunch(session) }, enabled = !busy) { Text("END LUNCH") }
                }
                ShiftState.COMPLETE -> {
                    Text("SHIFT COMPLETE", style = MaterialTheme.typography.headlineSmall)
                    Text("Paid: ${durations.paid.display()}")
                    Text("Store time: ${durations.store.display()}")
                    scheduled?.let { Text("Today's schedule: ${it.timeLabel()}") }
                    OutlinedButton(onClick = vm::clockIn, enabled = !busy) { Text("CLOCK IN — NEW SESSION") }
                }
                ShiftState.NOT_STARTED -> Unit
            }
            if (session.state != ShiftState.COMPLETE) Text(linked?.let { "Scheduled out: ${it.scheduledEnd.format(timeFormat)}${if (it.end.toLocalDate() != it.date) " (+1 day)" else ""}" } ?: "Unscheduled shift")
        }
        HorizontalDivider()
        Text("Personal estimates only. Not an official Publix app or employer timekeeping record.", style = MaterialTheme.typography.bodySmall)
    }
}
