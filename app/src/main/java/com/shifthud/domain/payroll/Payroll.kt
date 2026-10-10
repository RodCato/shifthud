package com.shifthud.domain.payroll

import com.shifthud.domain.analytics.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

const val MAX_PAYROLL_CENTS = 99_999_999_999L
enum class PayrollKind { DEDUCTION, EARNING }
data class PayrollLine(val id: Long = 0, val kind: PayrollKind, val label: String, val cents: Long? = null)
data class PayrollDeposit(val id: Long = 0, val date: LocalDate? = null, val cents: Long? = null)
data class Paycheck(val id: Long = 0, val periodStart: LocalDate, val periodEnd: LocalDate,
    val payDate: LocalDate? = null, val reportedHours: BigDecimal? = null, val grossCents: Long? = null,
    val netCents: Long? = null, val deductionsComplete: Boolean = false, val notes: String = "", val reference: String = "",
    val revision: Long = 0, val lines: List<PayrollLine> = emptyList(), val deposits: List<PayrollDeposit> = emptyList()) {
    val period get() = AnalyticsPeriod(periodStart, periodEnd.plusDays(1), false)
}
fun defaultPayrollLines() = listOf("Federal withholding", "Social Security", "Medicare", "State/local withholding", "Other deductions")
    .map { PayrollLine(kind=PayrollKind.DEDUCTION,label=it) } + PayrollLine(kind=PayrollKind.EARNING,label="Other earnings / adjustments")
fun parsePayrollHours(text: String): BigDecimal? {
    if(text.isBlank()) return null
    require(Regex("[0-9]+([.,][0-9]{1,6})?").matches(text.trim())) { "Enter decimal hours with up to 6 decimal places (39.15 = 39h 9m)." }
    return text.trim().replace(',','.').toBigDecimal().also { require(it <= BigDecimal("1000000")) { "Hours exceed the supported range." } }
}
fun parsePayrollCents(text: String): Long? {
    if(text.isBlank()) return null
    require(Regex("-?[0-9]+([.,][0-9]{1,2})?").matches(text.trim())) { "Enter a money amount with at most two decimal places." }
    val cents=text.trim().replace(',','.').toBigDecimal().movePointRight(2)
    require(cents.abs() <= BigDecimal.valueOf(MAX_PAYROLL_CENTS)) { "Money amount exceeds the supported range." }
    return cents.longValueExact()
}
fun centsInput(cents: Long?) = cents?.let { BigDecimal.valueOf(it,2).toPlainString() }.orEmpty()
fun decimalHours(paid: Duration): BigDecimal = BigDecimal.valueOf(paid.seconds).add(BigDecimal.valueOf(paid.nano.toLong(),9)).divide(BigDecimal("3600"),12,RoundingMode.HALF_UP)
fun employerDuration(hours: BigDecimal): Duration = Duration.ofNanos(hours.multiply(BigDecimal("3600000000000")).longValueExact())
fun employerHoursLabel(hours: BigDecimal): String {
    val duration=employerDuration(hours)
    val seconds=BigDecimal.valueOf(duration.seconds%60).add(BigDecimal.valueOf(duration.nano.toLong(),9)).stripTrailingZeros().toPlainString()
    return "${duration.toHours()}h ${duration.toMinutes()%60}m" + if(seconds=="0") "" else " ${seconds}s"
}
fun validatePaycheck(p: Paycheck) {
    require(p.periodEnd >= p.periodStart && p.periodEnd < LocalDate.MAX) { "Period end must be on or after its start." }
    p.reportedHours?.let { require(it >= BigDecimal.ZERO && it <= BigDecimal("1000000") && it.scale() <= 6) { "Invalid decimal hours." } }
    require(p.notes.length<=5000 && p.reference.length<=200) { "Notes or reference are too long." }
    (listOf(p.grossCents,p.netCents)+p.lines.map{it.cents}+p.deposits.map{it.cents}).filterNotNull().forEach { require(it in -MAX_PAYROLL_CENTS..MAX_PAYROLL_CENTS) { "Money amount exceeds the supported range." } }
    require(p.lines.all { it.label.isNotBlank() && it.label.length<=100 }) { "Every payroll category needs a label (up to 100 characters)." }
    require(p.lines.filter{it.id>0}.map{it.id}.distinct().size==p.lines.count{it.id>0}) { "Duplicate payroll line." }
    require(p.deposits.filter{it.id>0}.map{it.id}.distinct().size==p.deposits.count{it.id>0}) { "Duplicate deposit." }
}
enum class ReconciliationStatus(val label: String) { INCOMPLETE("Incomplete"), RECONCILED("Reconciled"), DIFFERENCE("Difference found") }
data class PayrollReconciliation(val paycheck: Paycheck, val estimate: WorkAnalytics) {
    private fun sum(values: List<Long>) = values.fold(0L,Math::addExact)
    val knownDeductions get() = sum(paycheck.lines.filter{it.kind==PayrollKind.DEDUCTION}.mapNotNull{it.cents})
    val deductions: Long? get() = knownDeductions.takeIf { paycheck.deductionsComplete && paycheck.lines.filter{it.kind==PayrollKind.DEDUCTION}.all{it.cents!=null} }
    val knownDeposits get() = sum(paycheck.deposits.mapNotNull{it.cents})
    val deposits: Long? get() = knownDeposits.takeIf { paycheck.deposits.isNotEmpty() && paycheck.deposits.all{it.cents!=null} }
    val hoursDifference get() = paycheck.reportedHours?.subtract(decimalHours(estimate.paid))
    val grossDifference get() = paycheck.grossCents?.let { gross -> estimate.grossCents?.let { Math.subtractExact(gross,it) } }
    // Earnings/adjustment lines describe amounts already included in actual gross; never add them twice.
    val calculatedNet get() = paycheck.grossCents?.let { gross -> deductions?.let { Math.subtractExact(gross,it) } }
    val netDifference get() = calculatedNet?.let { calculated -> paycheck.netCents?.let { Math.subtractExact(calculated,it) } }
    val depositDifference get() = paycheck.netCents?.let { net -> deposits?.let { Math.subtractExact(net,it) } }
    val complete get() = paycheck.payDate!=null && hoursDifference!=null && grossDifference!=null && netDifference!=null && depositDifference!=null && paycheck.deposits.all{it.date!=null} && estimate.activeSessions==0
    val status get() = when {
        !complete -> ReconciliationStatus.INCOMPLETE
        hoursDifference!!.compareTo(BigDecimal.ZERO)==0 && grossDifference==0L && netDifference==0L && depositDifference==0L -> ReconciliationStatus.RECONCILED
        else -> ReconciliationStatus.DIFFERENCE
    }
    val possibleHoursRounding get() = paycheck.reportedHours?.scale()?.let { it<=2 } == true && hoursDifference?.let { it.signum()!=0 && it.abs()<=BigDecimal("0.005") } == true
}
