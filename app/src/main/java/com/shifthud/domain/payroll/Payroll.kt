package com.shifthud.domain.payroll

import com.shifthud.domain.analytics.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

const val MAX_PAYROLL_CENTS = 99_999_999_999L
enum class PayrollKind { DEDUCTION, EARNING, NONCASH }
data class PayrollLine(val id: Long = 0, val kind: PayrollKind, val label: String, val cents: Long? = null,
    val taxable: Boolean? = null, val includedInGross: Boolean = false, val category: String? = null)
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
    require(p.lines.all { (it.category?.length ?: 0) <= 100 && (it.kind == PayrollKind.NONCASH || (it.taxable == null && !it.includedInGross && it.category == null)) }) { "Noncash classification belongs only to noncash entries." }
    require(p.lines.filter{it.id>0}.map{it.id}.distinct().size==p.lines.count{it.id>0}) { "Duplicate payroll line." }
    require(p.deposits.filter{it.id>0}.map{it.id}.distinct().size==p.deposits.count{it.id>0}) { "Duplicate deposit." }
}
enum class ReconciliationStatus(val label: String) { INCOMPLETE("Incomplete"), RECONCILED("Reconciled"), DIFFERENCE("Difference found") }
data class PayrollReconciliation(val paycheck: Paycheck, val estimate: WorkAnalytics) {
    private fun sum(values: List<Long>) = values.fold(0L,Math::addExact)
    val knownDeductions get() = confirmedDeductions(paycheck.copy(deductionsComplete=true)) ?: sum(paycheck.lines.filter{it.kind==PayrollKind.DEDUCTION && deductionCategory(it.label) !in setOf(DeductionCategory.TAX_TOTAL,DeductionCategory.ALL_TOTAL)}.mapNotNull{it.cents})
    val deductions: Long? get() = confirmedDeductions(paycheck)
    val knownDeposits get() = sum(paycheck.deposits.mapNotNull{it.cents})
    val deposits: Long? get() = knownDeposits.takeIf { paycheck.deposits.isNotEmpty() && paycheck.deposits.all{it.cents!=null} }
    val hoursDifference get() = paycheck.reportedHours?.subtract(decimalHours(estimate.paid))
    val grossDifference get() = cashWages(paycheck)?.let { gross -> estimate.grossCents?.let { Math.subtractExact(gross,it) } }
    // Earnings/adjustment lines describe amounts already included in actual gross; never add them twice.
    val calculatedNet get() = calculatedCashNet(paycheck)
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

/** Only explicitly classified amounts already included in gross reduce the cash paycheck. */
fun noncashCents(p: Paycheck, predicate: (PayrollLine) -> Boolean = { true }): Long? {
    val lines = p.lines.filter { it.kind == PayrollKind.NONCASH && predicate(it) }
    return if (lines.any { it.cents == null }) null else lines.fold(0L) { total, row -> Math.addExact(total, row.cents!!) }
}
fun cashWages(p: Paycheck): Long? = p.grossCents?.let { gross ->
    noncashCents(p) { it.includedInGross }?.let { Math.subtractExact(gross, it) }
}
/** Summary rows corroborate components, never add to them. Unknown labels remain legitimate deductions. */
fun confirmedDeductions(p: Paycheck): Long? {
    val rows = p.lines.filter { it.kind == PayrollKind.DEDUCTION }
    if (!p.deductionsComplete || rows.any { it.cents == null }) return null
    fun sum(lines: List<PayrollLine>) = lines.fold(0L) { total, row -> Math.addExact(total, row.cents!!) }
    val totals = rows.filter { deductionCategory(it.label) == DeductionCategory.ALL_TOTAL }
    val taxTotals = rows.filter { deductionCategory(it.label) == DeductionCategory.TAX_TOTAL }
    val taxes = rows.filter { deductionCategory(it.label) == DeductionCategory.TAX }
    if (totals.size > 1 || taxTotals.size > 1) return null
    if (taxTotals.isNotEmpty() && taxes.isNotEmpty() && sum(taxTotals) != sum(taxes)) return null
    val components = rows - totals - if (taxes.isNotEmpty()) taxTotals else emptyList()
    if (totals.isNotEmpty() && components.isNotEmpty() && sum(totals) != sum(components)) return null
    return sum(if (components.isEmpty()) totals else components)
}
fun calculatedCashNet(p: Paycheck): Long? = cashWages(p)?.let { cash -> confirmedDeductions(p)?.let { Math.subtractExact(cash,it) } }
fun cashNetDifference(p: Paycheck): Long? = calculatedCashNet(p)?.let { net -> p.netCents?.let { Math.subtractExact(net,it) } }
fun cashReconciliationWarning(p: Paycheck): String? = cashNetDifference(p)?.takeIf { it != 0L }?.let {
    "Unreconciled difference: $${centsInput(it)}" + if (kotlin.math.abs(it) == 1L) " (possible rounding difference; review)" else ""
}
