package com.shifthud.ui.time

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale

private fun Duration.exactSeconds() = BigDecimal.valueOf(seconds).add(BigDecimal.valueOf(nano.toLong(),9))
private fun decimal(value: BigDecimal, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits=2; maximumFractionDigits=2; roundingMode=RoundingMode.HALF_UP; isGroupingUsed=false
}.format(value)
fun decimalTime(duration: Duration, locale: Locale): String = decimal(duration.exactSeconds().divide(BigDecimal("3600"),2,RoundingMode.HALF_UP),locale)
fun reportedTime(hours: BigDecimal, locale: Locale): String = decimal(hours,locale)
/** Personal minus employer, computed before display rounding. Never used by payroll. */
fun timeComparison(personal: Duration, employer: BigDecimal?, locale: Locale): String? = employer?.let {
    val seconds=personal.exactSeconds().subtract(it.multiply(BigDecimal("3600")))
    val hours=seconds.divide(BigDecimal("3600"),2,RoundingMode.HALF_UP)
    val minutes=seconds.divide(BigDecimal("60"),0,RoundingMode.HALF_UP)
    fun sign(value: BigDecimal)=if(value.signum()>0) "+" else ""
    "Difference (ShiftHUD − Publix): ${sign(hours)}${decimal(hours,locale)} decimal hours (approximately ${sign(minutes)}${NumberFormat.getIntegerInstance(locale).format(minutes)}m)"
}
const val DECIMAL_TIME_NOTE="Personal punches may include seconds. Employer hours may already be rounded to hundredths; their displayed totals do not reveal original punch seconds. Daily values round independently, so their sum can differ from the exact weekly total rounded once. Differences may reflect timing, rounding or employer rules; records are never adjusted to match."
