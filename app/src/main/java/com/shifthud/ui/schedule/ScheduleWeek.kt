package com.shifthud.ui.schedule

import com.shifthud.domain.model.ScheduledShift
import com.shifthud.domain.pay.WorkWeek
import com.shifthud.domain.pay.workWeekFor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Membership follows the local scheduled start date, including overnight shifts. */
fun scheduleWeek(date: LocalDate) = workWeekFor(date)
fun WorkWeek.moveWeeks(amount: Long) = scheduleWeek(start.plusWeeks(amount))
fun WorkWeek.scheduleRows(shifts: List<ScheduledShift>) = shifts.filter { it.date in this }
    .sortedWith(compareBy<ScheduledShift> { it.date }.thenBy { it.scheduledStart }.thenBy { it.id })
fun WorkWeek.scheduleLabel(locale: Locale): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    return "${start.format(format)} – ${endInclusive.format(format)}"
}
