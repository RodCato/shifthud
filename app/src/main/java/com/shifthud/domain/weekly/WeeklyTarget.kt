package com.shifthud.domain.weekly

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import java.time.*

val WEEKLY_WARNING_MINUTES = setOf(120, 30, 10)
data class WeeklyTargetSettings(val enabled: Boolean = true, val targetMinutes: Int = 2400,
    val warnings: Set<Int> = WEEKLY_WARNING_MINUTES) {
    init { require(targetMinutes in 1..10080); require(warnings.all { it in WEEKLY_WARNING_MINUTES }) }
    val target: Duration get() = Duration.ofMinutes(targetMinutes.toLong())
}

data class WeeklyTargetProgress(val week: WorkWeek, val paid: Duration, val target: Duration,
    val projected: Duration, val projectedClockOut: Instant?, val lunchEstimate: Boolean,
    val active: WorkSession?, val correctionKey: String) {
    val remaining: Duration get() = target.minus(paid).coerceAtLeast(Duration.ZERO)
    val progress: Double get() = paid.toMillis().toDouble() / target.toMillis()
    val overage: Duration get() = projected.minus(target).coerceAtLeast(Duration.ZERO)
    val shortfall: Duration get() = target.minus(projected).coerceAtLeast(Duration.ZERO)
    val status: String get() = when { paid > target -> "OVER TARGET"; paid == target -> "TARGET REACHED"; else -> "WEEKLY TARGET" }
}

/** Actual totals use exactly the gross estimator's session selection and precision. */
fun weeklyTarget(sessions: List<WorkSession>, schedule: List<ScheduledShift>, settings: WeeklyTargetSettings,
                 defaultLunchMinutes: Int, now: Instant, zone: ZoneId): WeeklyTargetProgress {
    val week = workWeekFor(now.atZone(zone).toLocalDate())
    val records = sessions.filter { it.clockIn <= now && it.clockIn.atZone(zone).toLocalDate() in week }
    val paid = PayEstimator(ShiftEngine()).weekBreakdown(records, emptyList(), now, zone).paid
    val active = records.firstOrNull { it.state == ShiftState.WORKING || it.state == ShiftState.ON_LUNCH }
    val remaining = settings.target.minus(paid).coerceAtLeast(Duration.ZERO)
    val resume = when (active?.state) {
        ShiftState.WORKING -> now
        ShiftState.ON_LUNCH -> active.autoLunchEndTarget?.let { maxOf(now, it) }
        else -> null
    }
    var scheduledRemaining = Duration.ZERO
    schedule.filter { it.date in week }.forEach { shift ->
        val linked = sessions.filter { it.scheduledShiftId == shift.id && it.clockIn <= now }
        val live = linked.firstOrNull { it.state != ShiftState.COMPLETE }
        // Completed records replace the schedule entirely; never add planned time twice.
        if (linked.isNotEmpty() && live == null) return@forEach
        // An overnight active record attributed to last week cannot contribute to this week.
        if (live != null && live.clockIn.atZone(zone).toLocalDate() !in week) return@forEach
        val start = if (live != null) now else maxOf(now, shift.start.atZone(zone).toInstant())
        val end = shift.end.atZone(zone).toInstant()
        if (end <= start) return@forEach
        val plannedLunch = Duration.ofMinutes((shift.plannedLunchMinutes ?: defaultLunchMinutes).toLong())
        val unpaidRemaining = when {
            live?.lunchEnd != null -> Duration.ZERO
            live?.state == ShiftState.ON_LUNCH -> live.autoLunchEndTarget?.let { Duration.between(now, it).coerceAtLeast(Duration.ZERO) }
                ?: plannedLunch.minus(Duration.between(live.lunchStart, now).coerceAtLeast(Duration.ZERO)).coerceAtLeast(Duration.ZERO)
            else -> plannedLunch
        }
        scheduledRemaining = scheduledRemaining.plus(Duration.between(start, end).minus(unpaidRemaining).coerceAtLeast(Duration.ZERO))
    }
    // IDs/revisions detect additions, deletions and corrected punches, never elapsed-time ticks.
    val correctionKey = records.sortedBy { it.id }.joinToString(";") { "${it.id}:${it.correctionRevision}" }
    return WeeklyTargetProgress(week, paid, settings.target, paid.plus(scheduledRemaining),
        resume?.takeIf { !remaining.isZero }?.plus(remaining), active?.state == ShiftState.ON_LUNCH, active, correctionKey)
}

fun parseWeeklyTargetMinutes(input: String): Int {
    val hours = input.trim().replace(',', '.').toBigDecimal()
    require(hours > java.math.BigDecimal.ZERO && hours <= java.math.BigDecimal(168))
    return hours.multiply(java.math.BigDecimal(60)).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact().also { require(it in 1..10080) }
}
