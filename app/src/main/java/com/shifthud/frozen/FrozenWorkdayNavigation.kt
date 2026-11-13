package com.shifthud.frozen

import com.shifthud.domain.model.ScheduledShift
import java.time.LocalDate

/** Dates come only from saved shifts, never from weekday templates. Equal times are overnight shifts. */
fun nextFrozenWorkday(today: LocalDate, schedule: List<ScheduledShift>): LocalDate? =
    schedule.asSequence().filter { it.date > today && runCatching { it.end > it.start }.getOrDefault(false) }
        .map { it.date }.minOrNull()

fun initialFrozenWorkday(today: LocalDate, schedule: List<ScheduledShift>): LocalDate =
    if (schedule.any { it.date == today && runCatching { it.end > it.start }.getOrDefault(false) }) today
    else nextFrozenWorkday(today, schedule) ?: today
