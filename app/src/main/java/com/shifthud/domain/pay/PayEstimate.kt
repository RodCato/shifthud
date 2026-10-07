package com.shifthud.domain.pay

import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.ShiftEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.*
import java.util.Currency
import java.util.Locale

const val MAX_RATE_CENTS = 100_000L // $1,000/hour

data class PayRate(val effectiveFrom: LocalDate, val centsPerHour: Long) {
    init { require(centsPerHour in 1..MAX_RATE_CENTS) }
}
val DEFAULT_PAY_RATES = listOf(PayRate(LocalDate.MIN, 1600))

fun parseRateCents(input: String): Long {
    val value = input.trim()
    require(Regex("[0-9]+([.,][0-9]{1,2})?").matches(value)) { "Enter a rate with at most two decimal places." }
    val cents = value.replace(',', '.').toBigDecimal().movePointRight(2)
    require(cents > BigDecimal.ZERO && cents <= BigDecimal.valueOf(MAX_RATE_CENTS)) { "Enter $0.01–$1,000.00 per hour." }
    return cents.longValueExact()
}

fun applicableRate(rates: List<PayRate>, clockIn: Instant, zone: ZoneId): PayRate =
    requireNotNull(rates.filter { it.effectiveFrom <= clockIn.atZone(zone).toLocalDate() }.maxByOrNull { it.effectiveFrom })

fun grossCents(paid: Duration, centsPerHour: Long): Long {
    require(!paid.isNegative && centsPerHour in 1..MAX_RATE_CENTS)
    return BigDecimal.valueOf(paid.toMillis()).multiply(BigDecimal.valueOf(centsPerHour))
        .divide(BigDecimal.valueOf(3_600_000), 0, RoundingMode.HALF_UP).longValueExact()
}

fun money(cents: Long, locale: Locale): String = NumberFormat.getCurrencyInstance(locale).apply {
    currency = Currency.getInstance("USD")
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}.format(BigDecimal.valueOf(cents, 2))

data class PayEstimate(val paid: Duration, val grossCents: Long)
class PayEstimator(private val engine: ShiftEngine) {
    fun session(session: WorkSession, rates: List<PayRate>, now: Instant, zone: ZoneId): PayEstimate {
        val paid = engine.durations(session, 360, now).paid
        return PayEstimate(paid, grossCents(paid, applicableRate(rates, session.clockIn, zone).centsPerHour))
    }

    // Assign whole sessions by local clock-in date; cap elapsed work at now, not at week end.
    // Round each session's contribution once, then sum cents so displayed shift totals reconcile.
    fun week(sessions: List<WorkSession>, rates: List<PayRate>, now: Instant, zone: ZoneId): PayEstimate {
        val week = workWeekFor(now.atZone(zone).toLocalDate())
        var paid = Duration.ZERO
        var cents = 0L
        sessions.forEach { session ->
            if (session.clockIn.atZone(zone).toLocalDate() !in week) return@forEach
            val end = minOf(session.clockOut ?: now, now)
            val start = session.clockIn
            if (end > start) {
                val lunchStart = maxOf(session.lunchStart ?: end, start)
                val lunchEnd = minOf(session.lunchEnd ?: end, end)
                val lunch = if (lunchEnd > lunchStart) Duration.between(lunchStart, lunchEnd) else Duration.ZERO
                val contribution = Duration.between(start, end).minus(lunch)
                paid = paid.plus(contribution)
                cents = Math.addExact(cents, grossCents(contribution, applicableRate(rates, session.clockIn, zone).centsPerHour))
            }
        }
        return PayEstimate(paid, cents)
    }
}
