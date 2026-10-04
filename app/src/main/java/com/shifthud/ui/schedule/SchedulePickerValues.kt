package com.shifthud.ui.schedule

import com.shifthud.domain.model.ScheduledShift
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal data class SchedulePickerValues(
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
) {
    companion object {
        fun initial(existing: ScheduledShift?, today: LocalDate) = SchedulePickerValues(
            existing?.date ?: today,
            existing?.scheduledStart ?: LocalTime.of(4, 0),
            existing?.scheduledEnd ?: LocalTime.of(13, 0),
        )
    }
}

// Material DatePicker represents calendar dates at midnight UTC, not device-local midnight.
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun dateFromPickerMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
internal fun timeFromPicker(hour: Int, minute: Int): LocalTime = LocalTime.of(hour, minute)
internal fun LocalDate.editorLabel(locale: Locale): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
internal fun LocalTime.editorLabel(locale: Locale): String = format(DateTimeFormatter.ofPattern("h:mm a", locale))
