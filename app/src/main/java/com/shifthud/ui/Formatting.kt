package com.shifthud.ui
import com.shifthud.domain.model.ScheduledShift
import java.time.Duration
import java.time.format.DateTimeFormatter

val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
fun ScheduledShift.timeLabel(): String = "${scheduledStart.format(timeFormat)} – ${scheduledEnd.format(timeFormat)}" + if (end.toLocalDate() != date) " (+1 day)" else ""
fun Duration.display(): String { val minutes = toMinutes().coerceAtLeast(0); return "${minutes / 60}h ${minutes % 60}m" }
