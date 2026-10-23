package com.shifthud.domain.payroll

import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.pay.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

/** Saved records are user-reviewed facts; the schema has no separate confirmation flag.
 * Missing fields remain missing independently of reconciliation against recorded work. */
data class EarningsAmount(val known: BigDecimal, val missing: Int, val records: Int) {
    val complete get() = records > 0 && missing == 0
}
private fun amount(checks: List<Paycheck>, field: (Paycheck) -> BigDecimal?): EarningsAmount {
    val values = checks.map(field)
    return EarningsAmount(values.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add), values.count { it == null }, checks.size)
}
private fun Long?.decimal() = this?.let { BigDecimal.valueOf(it, 2) }
enum class DeductionCategory { TAX, OTHER, UNKNOWN, TAX_TOTAL, ALL_TOTAL }
fun deductionCategory(label: String): DeductionCategory {
    val name = label.lowercase().trim()
    return when {
        ("total" in name && listOf("tax", "withholding").any { it in name }) || name == "taxes withheld" -> DeductionCategory.TAX_TOTAL
        name in setOf("total deductions", "deductions total") -> DeductionCategory.ALL_TOTAL
        listOf("tax", "withholding", "social security", "medicare", "fica").any { it in name } -> DeductionCategory.TAX
        listOf("deduction", "401", "insurance", "dental", "vision", "union dues", "garnishment", "retirement").any { it in name } -> DeductionCategory.OTHER
        else -> DeductionCategory.UNKNOWN
    }
}
data class EarningsDeductions(val taxes: BigDecimal?, val other: BigDecimal?, val components: Map<String, BigDecimal>, val warnings: List<String>)
fun earningsDeductions(p: Paycheck): EarningsDeductions {
    val lines = p.lines.filter { it.kind == PayrollKind.DEDUCTION }
    val groups = lines.groupBy { deductionCategory(it.label) }
    val warnings = mutableListOf<String>()
    fun sum(kind: DeductionCategory): BigDecimal? {
        val rows = groups[kind].orEmpty()
        return if (rows.any { it.cents == null }) null else rows.fold(BigDecimal.ZERO) { total, row -> total + row.cents.decimal()!! }
    }
    val taxRows = groups[DeductionCategory.TAX].orEmpty()
    val summaries = groups[DeductionCategory.TAX_TOTAL].orEmpty()
    var tax = if (summaries.size == 1) summaries.single().cents.decimal() else sum(DeductionCategory.TAX)
    if (summaries.size > 1 || (summaries.isNotEmpty() && taxRows.isNotEmpty() && tax != sum(DeductionCategory.TAX))) {
        warnings += "Tax summary and components need review"; tax = null
    }
    val unknown = groups[DeductionCategory.UNKNOWN].orEmpty()
    if (unknown.isNotEmpty()) warnings += "Unclassified deductions: ${unknown.joinToString { it.label }}"
    val allTotals = groups[DeductionCategory.ALL_TOTAL].orEmpty()
    if (allTotals.isNotEmpty()) warnings += "Total deductions row needs review; not added to components"
    val duplicateLines = lines.groupBy { it.label.trim().lowercase() to it.cents }.values.any { it.size > 1 }
    if (duplicateLines) warnings += "Repeated deduction rows need review"
    val complete = !duplicateLines && p.deductionsComplete && lines.all { it.cents != null } && unknown.isEmpty() && allTotals.isEmpty()
    val other = if (complete) sum(DeductionCategory.OTHER) else null
    if (!complete) tax = null
    val components = (if (duplicateLines) emptyList() else taxRows).filter { it.cents != null }.groupBy { it.label }.mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { total, row -> total + row.cents.decimal()!! } }
    if (tax != null && other != null && p.grossCents != null && p.netCents != null &&
        p.grossCents.decimal()!! - tax - other != p.netCents.decimal()) warnings += "Gross minus taxes and deductions does not equal net"
    return EarningsDeductions(tax, other, components, warnings)
}
data class EarningsTotals(val checks: List<Paycheck>) {
    val gross = amount(checks) { it.grossCents.decimal() }
    val net = amount(checks) { it.netCents.decimal() }
    val taxes = amount(checks) { earningsDeductions(it).taxes }
    val other = amount(checks) { earningsDeductions(it).other }
    val hours = amount(checks) { it.reportedHours }
    private fun percentage(value: EarningsAmount) = if (gross.complete && value.complete && gross.known.signum() > 0)
        value.known.multiply(BigDecimal(100)).divide(gross.known, 1, RoundingMode.HALF_UP) else null
    val takeHomeRate get() = percentage(net)
    val withholdingRate get() = percentage(taxes)
    val taxComponents get() = checks.flatMap { earningsDeductions(it).components.entries }.groupBy { it.key }
        .mapValues { (_, entries) -> entries.fold(BigDecimal.ZERO) { total, entry -> total + entry.value } }
}
data class EarningsPeriod(val year: Int? = null, val month: Int? = null) {
    fun includes(p: Paycheck) = year == null || p.payDate?.let { it.year == year && (month == null || it.monthValue == month) } == true
}
data class EarningsOverview(val checks: List<Paycheck>, val review: List<Paycheck>) {
    fun totals(period: EarningsPeriod = EarningsPeriod()) = EarningsTotals(checks.filter(period::includes))
    fun months(period: EarningsPeriod) = checks.filter(period::includes).filter { it.payDate != null }
        .groupBy { YearMonth.from(it.payDate) }.toSortedMap(reverseOrder())
}
fun earningsOverview(records: List<Paycheck>): EarningsOverview {
    // Repository enforces one record per exact work period. Defensively quarantine overlaps,
    // including imported legacy data, instead of silently merging potentially distinct checks.
    val recordsById = records.distinctBy { it.id }
    val ambiguous = recordsById.filter { a -> recordsById.any { b -> a.id != b.id && a.periodStart <= b.periodEnd && b.periodStart <= a.periodEnd } }
    val ids = ambiguous.map { it.id }.toSet()
    return EarningsOverview(recordsById.filter { it.id !in ids }.sortedWith(compareByDescending<Paycheck> { it.payDate }.thenByDescending { it.id }), ambiguous)
}
enum class CoverageStatus(val label: String) {
    CONFIRMED("Confirmed paycheck"), UNRECORDED("Paystub not yet recorded"), CURRENT("Workweek not yet payable"),
    INCOMPLETE("Incomplete workweek data"), UNCERTAIN("Uncertain matching — review payroll periods")
}
data class EarningsCoverage(val start: LocalDate, val end: LocalDate, val status: CoverageStatus, val paid: Duration, val estimatedGross: BigDecimal?)
fun earningsCoverage(sessions: List<WorkSession>, records: List<Paycheck>, rates: List<PayRate>, now: Instant, zone: ZoneId, estimator: PayEstimator): List<EarningsCoverage> {
    val overview = earningsOverview(records)
    return sessions.distinctBy { it.id }.filter { it.clockIn <= now }.groupBy { workWeekFor(it.clockIn.atZone(zone).toLocalDate()).start }.map { (start, rows) ->
        val end = start.plusDays(6)
        val matches = records.filter { it.periodStart <= end && it.periodEnd >= start }
        val status = when {
            matches.any { it in overview.review } || matches.size > 1 -> CoverageStatus.UNCERTAIN
            matches.size == 1 && matches.single().periodStart <= start && matches.single().periodEnd >= end -> CoverageStatus.CONFIRMED
            matches.isNotEmpty() -> CoverageStatus.UNCERTAIN
            rows.any { it.clockOut == null || it.clockOut <= it.clockIn } -> CoverageStatus.INCOMPLETE
            end >= now.atZone(zone).toLocalDate() -> CoverageStatus.CURRENT
            else -> CoverageStatus.UNRECORDED
        }
        val completed = rows.filter { it.clockOut != null && it.clockOut > it.clockIn && it.clockOut <= now }
        val contributions = estimator.breakdown(completed, rates, now, zone).contributions
        EarningsCoverage(start, end, status, contributions.fold(Duration.ZERO) { total, row -> total + row.paid },
            if (contributions.any { it.grossCents == null }) null else contributions.fold(BigDecimal.ZERO) { total, row -> total + row.grossCents.decimal()!! })
    }.sortedByDescending { it.start }
}
