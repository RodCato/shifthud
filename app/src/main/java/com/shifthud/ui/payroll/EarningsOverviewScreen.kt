package com.shifthud.ui.payroll

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.shifthud.domain.payroll.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.*
import java.time.format.*
import java.util.Currency

@Composable
fun EarningsOverviewScreen(records: List<Paycheck>, coverage: List<EarningsCoverage>, back: () -> Unit, open: (Paycheck) -> Unit) {
    val overview = remember(records) { earningsOverview(records) }
    var mode by rememberSaveable { mutableStateOf("ALL TIME") }
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var month by rememberSaveable { mutableIntStateOf(LocalDate.now().monthValue) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseYear by remember { mutableStateOf(false) }
    var chooseMonth by remember { mutableStateOf(false) }
    var taxes by rememberSaveable { mutableStateOf(false) }
    val period = EarningsPeriod(if (mode=="ALL TIME") null else year, if (mode=="MONTH") month else null)
    val totals = overview.totals(period)
    val locale = LocalConfiguration.current.locales[0]
    val currency = remember(locale) { NumberFormat.getCurrencyInstance(locale).apply { currency=Currency.getInstance("USD") } }
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    fun dollars(value: BigDecimal) = currency.format(value)
    fun metric(value: EarningsAmount, money: Boolean = true): String {
        if (value.records==0 || value.missing==value.records) return "Not available"
        val number = if (money) dollars(value.known) else value.known.stripTrailingZeros().toPlainString()
        return if (value.missing>0) "$number known · ${value.missing} missing" else number
    }
    @Composable fun history(p: Paycheck, review: Boolean = false) {
        OutlinedCard(onClick={open(p)},modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(p.payDate?.format(dates) ?: "Pay date not entered",style=MaterialTheme.typography.titleMedium)
                Text("Workweek: ${p.periodStart.format(dates)} – ${p.periodEnd.format(dates)}")
                Text("Gross: ${p.grossCents?.let { dollars(BigDecimal.valueOf(it,2)) } ?: "Not entered"}")
                Text("Net: ${p.netCents?.let { dollars(BigDecimal.valueOf(it,2)) } ?: "Not entered"}")
                Text("Hours: ${p.reportedHours?.toPlainString() ?: "Not entered"}")
                Text(if (review) "Review overlap · excluded from totals" else "Confirmed entered values · saved payroll")
                earningsDeductions(p).warnings.forEach { Text(it,color=MaterialTheme.colorScheme.error) }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription="Earnings overview" },contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=back) { Text("‹ BACK TO PAYCHECKS") }; Text("Earnings Overview",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() }) }
        item {
            // Wrapping buttons remain usable with narrow screens and enlarged fonts.
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("ALL TIME","YEAR","MONTH").forEach { value -> FilterChip(selected=mode==value,onClick={mode=value},label={Text(value)}) }
            }
            if (mode!="ALL TIME") {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={if (mode=="YEAR") year-- else YearMonth.of(year,month).minusMonths(1).let { year=it.year;month=it.monthValue }},modifier=Modifier.semantics { contentDescription="Previous period" }) { Text("‹") }
                    Text(if (mode=="YEAR") year.toString() else YearMonth.of(year,month).format(DateTimeFormatter.ofPattern("MMMM uuuu",locale)),Modifier.padding(vertical=12.dp))
                    OutlinedButton(onClick={if (mode=="YEAR") year++ else YearMonth.of(year,month).plusMonths(1).let { year=it.year;month=it.monthValue }},modifier=Modifier.semantics { contentDescription="Next period" }) { Text("›") }
                }
            }
            if (mode!="ALL TIME") FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box {
                    TextButton(onClick={chooseYear=true}) { Text("SELECT YEAR") }
                    DropdownMenu(expanded=chooseYear,onDismissRequest={chooseYear=false}) {
                        (records.mapNotNull { it.payDate?.year }+LocalDate.now().year+year).distinct().sortedDescending().forEach { value ->
                            DropdownMenuItem(text={Text(value.toString())},onClick={year=value;chooseYear=false})
                        }
                    }
                }
                if (mode=="MONTH") Box {
                    TextButton(onClick={chooseMonth=true}) { Text("SELECT MONTH") }
                    DropdownMenu(expanded=chooseMonth,onDismissRequest={chooseMonth=false}) {
                        (1..12).forEach { value -> DropdownMenuItem(text={Text(Month.of(value).getDisplayName(TextStyle.FULL,locale))},onClick={month=value;chooseMonth=false}) }
                    }
                }
            }
            Text("Grouped by paycheck date",style=MaterialTheme.typography.bodySmall)
            if (mode!="ALL TIME" && overview.checks.any { it.payDate==null }) Text("Records without pay dates appear only in All Time.")
        }
        if (totals.checks.isEmpty()) item { Text("No confirmed paychecks in this period. Import a paystub or enter reviewed payroll facts in Paychecks.") }
        else item { EarningsCard(if (mode=="ALL TIME") "DOCUMENTED LIFETIME EARNINGS" else "DOCUMENTED EARNINGS") {
            Text(metric(totals.net),style=MaterialTheme.typography.headlineMedium)
            Text("Total Take-Home Pay")
            Text("Total Gross Pay: ${metric(totals.gross)}")
            Text("Total Taxes Withheld: ${metric(totals.taxes)}")
            Text("Other Deductions: ${metric(totals.other)}")
            Text("Total Paid Hours: ${metric(totals.hours,false)}")
            Text("Paychecks Recorded: ${totals.checks.size}")
            Text("Take-Home Rate: ${totals.takeHomeRate?.let { "$it%" } ?: "Unavailable — incomplete values"}")
            Text("Totals reflect ${totals.checks.size} confirmed paychecks. Additional paystubs may be missing.",style=MaterialTheme.typography.bodySmall)
        } }
        item {
            TextButton(onClick={taxes=!taxes},modifier=Modifier.semantics { stateDescription=if(taxes) "Expanded" else "Collapsed" }) { Text("TAXES WITHHELD") }
            if (taxes) EarningsCard("Confirmed withholding") {
                Text("Total Taxes Withheld: ${metric(totals.taxes)}")
                Text("Effective Withholding Percentage: ${totals.withholdingRate?.let { "$it%" } ?: "Unavailable"}")
                Text("Withholding only, not final income-tax liability.",style=MaterialTheme.typography.bodySmall)
                totals.taxComponents.forEach { (label, value) -> Text("$label: ${dollars(value)}") }
                Text("Components show recorded labels only; may be partial. Summary rows are never added to components.",style=MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("MONTHLY BREAKDOWN",style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() }) }
        overview.months(period).forEach { (key,checks) ->
            item(key="month-$key") {
                val values=EarningsTotals(checks)
                OutlinedButton(onClick={expanded=if(expanded==key.toString()) null else key.toString()},modifier=Modifier.fillMaxWidth().semantics { stateDescription=if(expanded==key.toString()) "Expanded" else "Collapsed" }) { Text("${key.format(DateTimeFormatter.ofPattern("MMMM uuuu",locale))} · ${metric(values.net)} net") }
                if(expanded==key.toString()) EarningsCard("Monthly payroll") {
                    Text("Gross: ${metric(values.gross)}\nTaxes: ${metric(values.taxes)}\nOther deductions: ${metric(values.other)}\nTake-home: ${metric(values.net)}\nHours paid: ${metric(values.hours,false)}\nPaychecks: ${checks.size}")
                    checks.forEach { history(it) }
                }
            }
        }
        if(overview.review.isNotEmpty()) {
            item { Text("REVIEW POSSIBLE DUPLICATES",color=MaterialTheme.colorScheme.error);Text("Overlapping work periods are excluded. Open each original record to correct its period; no payroll facts were changed.") }
            items(overview.review,key={"review-${it.id}"}) { history(it,true) }
        }
        item { Text("PAYCHECK HISTORY",style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() }) }
        items(totals.checks,key={"check-${it.id}"}) { history(it) }
        item { EarningsCard("PAYSTUB COVERAGE · ALL RECORDED WORK") {
            Text("${overview.checks.size} confirmed paychecks")
            Text("${coverage.count { it.status==CoverageStatus.UNRECORDED }} workweeks without a recorded paystub")
            Text("No payroll cadence is stored. Unrecorded paystubs are not labeled overdue; recorded sessions cannot prove a complete workweek.",style=MaterialTheme.typography.bodySmall)
            if(coverage.isEmpty()) Text("No recorded workweeks to compare.")
        } }
        items(coverage,key={"coverage-${it.start}"}) { row -> Text("${row.start.format(dates)} – ${row.end.format(dates)}\n${row.status.label}") }
        item { EarningsCard("WORKED — NOT YET PAID · ALL RECORDED WORK") {
            val pending=coverage.filter { it.status==CoverageStatus.UNRECORDED || it.status==CoverageStatus.CURRENT }
            val uncertain=coverage.any { it.status==CoverageStatus.UNCERTAIN || it.status==CoverageStatus.INCOMPLETE }
            Text("Recorded completed work without a linked paycheck; payment itself is not verified.",style=MaterialTheme.typography.bodySmall)
            if(uncertain) Text("Pending totals uncertain — review incomplete work or overlapping payroll periods.")
            else if(pending.isEmpty()) Text("No unmatched completed work recorded.")
            else {
                Text("Recorded paid hours: ${decimalHours(pending.fold(Duration.ZERO) { sum,row -> sum+row.paid }).stripTrailingZeros().toPlainString()}")
                Text("Estimated Pending Gross: ${if(pending.any { it.estimatedGross==null }) "Unavailable — missing rate history" else dollars(pending.fold(BigDecimal.ZERO) { sum,row -> sum+row.estimatedGross!! })}")
            }
            Text("Estimates are excluded from all confirmed earnings. No take-home estimate.",style=MaterialTheme.typography.bodySmall)
        } }
    }
}
@Composable private fun EarningsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() });content()
    } }
}
