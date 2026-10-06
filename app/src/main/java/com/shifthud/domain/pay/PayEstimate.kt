package com.shifthud.domain.pay

import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.ShiftEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.*
import java.time.temporal.TemporalAdjusters
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
data class WeekWindow(val start: Instant, val endExclusive: Instant)
fun currentWeek(now: Instant, zone: ZoneId): WeekWindow {
    val monday = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return WeekWindow(monday.atStartOfDay(zone).toInstant(), monday.plusWeeks(1).atStartOfDay(zone).toInstant())
}

class PayEstimator(private val engine: ShiftEngine) {
    fun session(session: WorkSession, rates: List<PayRate>, now: Instant, zone: ZoneId): PayEstimate {
        val paid = engine.durations(session, 360, now).paid
        return PayEstimate(paid, grossCents(paid, applicableRate(rates, session.clockIn, zone).centsPerHour))
    }

    // Clip actual paid intervals to this week and now. Calendar weeks can be 167/169 hours at DST.
    // Round each session's contribution once, then sum cents so displayed shift totals reconcile.
    fun week(sessions: List<WorkSession>, rates: List<PayRate>, now: Instant, zone: ZoneId): PayEstimate {
        val window = currentWeek(now, zone)
        var paid = Duration.ZERO
        var cents = 0L
        sessions.forEach { session ->
            val end = minOf(session.clockOut ?: now, now, window.endExclusive)
            val start = maxOf(session.clockIn, window.start)
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
