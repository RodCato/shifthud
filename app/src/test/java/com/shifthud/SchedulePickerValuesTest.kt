package com.shifthud

import com.shifthud.domain.model.ScheduledShift
import com.shifthud.ui.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale
import java.util.TimeZone

class SchedulePickerValuesTest {
    @Test fun dateDisplayUsesFriendlyLocalizedFormat() {
        val date = LocalDate.of(2026, 10, 5)
        assertEquals("Oct 5, 2026", date.editorLabel(Locale.US))
        assertEquals("05.10.2026", date.editorLabel(Locale.GERMANY))
    }

    @Test fun timeDisplayUsesTwelveHourClockWithAmPm() {
        assertEquals("4:00 AM", LocalTime.of(4, 0).editorLabel(Locale.US))
        assertEquals("1:05 PM", LocalTime.of(13, 5).editorLabel(Locale.US))
        assertEquals("12:00 AM", LocalTime.MIDNIGHT.editorLabel(Locale.US))
        assertEquals("12:00 PM", LocalTime.NOON.editorLabel(Locale.US))
    }

    @Test fun calendarSelectionMapsUtcMillisToDateInEveryDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Los_Angeles", "Pacific/Kiritimati", "America/Chicago")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val millis = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
                assertEquals(LocalDate.of(2026, 10, 5), dateFromPickerMillis(millis))
                assertEquals(millis, LocalDate.of(2026, 10, 5).toPickerMillis())
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun leapDayAndDstDatesRoundTrip() {
        listOf("2028-02-29", "2026-03-08", "2026-11-01").forEach {
            val date = LocalDate.parse(it)
            assertEquals(date, dateFromPickerMillis(date.toPickerMillis()))
        }
    }

    @Test fun clockSelectionMapsToLocalTime() {
        assertEquals(LocalTime.of(23, 45), timeFromPicker(23, 45))
        assertEquals(LocalTime.MIDNIGHT, timeFromPicker(0, 0))
        assertEquals(LocalTime.NOON, timeFromPicker(12, 0))
    }

    @Test fun editingPreservesSavedValuesUntilASelectionIsConfirmed() {
        val saved = ScheduledShift(7, LocalDate.of(2026, 10, 5), LocalTime.of(23, 15, 30), LocalTime.of(4, 5), 60, "Overnight")
        val initial = SchedulePickerValues.initial(saved, LocalDate.of(2026, 10, 4))
        // Opening/canceling a picker does not replace the form values.
        val pendingSelection = timeFromPicker(22, 30)
        assertEquals(saved.date, initial.date)
        assertEquals(saved.scheduledStart, initial.start)
        assertEquals(saved.scheduledEnd, initial.end)
        val changed = initial.copy(start = pendingSelection)
        assertEquals(LocalTime.of(22, 30), changed.start)
        assertEquals(saved.date, changed.date)
        assertEquals(saved.scheduledEnd, changed.end)
        assertEquals(LocalTime.of(23, 15, 30), saved.scheduledStart)
    }

    @Test fun addingStartsWithTodayAndExistingDefaultTimes() {
        val today = LocalDate.of(2026, 10, 5)
        assertEquals(SchedulePickerValues(today, LocalTime.of(4, 0), LocalTime.of(13, 0)), SchedulePickerValues.initial(null, today))
    }

    @Test fun pickerSelectedOvernightShiftKeepsNextDayEnd() {
        val date = dateFromPickerMillis(LocalDate.of(2026, 10, 5).toPickerMillis())
        val shift = ScheduledShift(date = date, scheduledStart = timeFromPicker(23, 0), scheduledEnd = timeFromPicker(4, 0))
        assertEquals(LocalDateTime.of(2026, 10, 6, 4, 0), shift.end)
        assertEquals(Duration.ofHours(5), shift.duration(ZoneOffset.UTC))
    }

    @Test fun equalPickerTimesStillMeanFollowingDay() {
        val shift = ScheduledShift(date = LocalDate.of(2026, 10, 5), scheduledStart = timeFromPicker(4, 0), scheduledEnd = timeFromPicker(4, 0))
        assertEquals(Duration.ofHours(24), shift.duration(ZoneOffset.UTC))
    }
}
