package com.shifthud.ui.payroll

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.domain.calendar.recordDuration
import com.shifthud.domain.pay.money
import com.shifthud.domain.payroll.*
import com.shifthud.ui.schedule.ScheduleDatePicker
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.*

@Composable fun PaychecksScreen(vm:PaychecksViewModel,back:()->Unit,importImage:(LocalDate,LocalDate)->Unit = {_,_->}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by rememberSaveable{mutableStateOf<String?>(null)}
    var selecting by rememberSaveable{mutableStateOf(false)}
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dates=DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val p=state.draft
    LaunchedEffect(p?.periodStart,p?.periodEnd){if(p!=null)list.scrollToItem(0)}
    fun moneyOrMissing(cents:Long?)=cents?.let{money(it,locale)}?:"Not entered"
    fun signed(cents:Long?)=cents?.let{(if(it>0)"+" else "")+money(it,locale)}?:"Not yet comparable"
    fun edit(value:Paycheck){editing=PayrollDraft.from(value).serialize()}
    LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=back){Text("‹ WORK ANALYTICS")};Text("Paychecks",style=MaterialTheme.typography.headlineMedium);Text("Manual payroll comparison · Personal tracker, not official Publix payroll.",style=MaterialTheme.typography.bodySmall) }
        if(state.error!=null)item{Text(state.error!!,color=MaterialTheme.colorScheme.error);Button(onClick=vm::retry){Text("RETRY")}}
        else if(p==null || state.estimate==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        else {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={vm.week(p.periodStart.minusWeeks(1))},modifier=Modifier.semantics{contentDescription="Previous workweek"}){Text("‹")}
                    Text("${p.periodStart.format(dates)} –\n${p.periodEnd.format(dates)}",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={vm.week(p.periodStart.plusWeeks(1))},modifier=Modifier.semantics{contentDescription="Next workweek"}){Text("›")}
                }
                OutlinedButton(onClick={selecting=true}){Text("SELECT WORKWEEK")}
                OutlinedButton(enabled=!busy,onClick={importImage(p.periodStart,p.periodEnd)}){Text("Import Paystub Screenshot")}
                Button(enabled=!busy,onClick={edit(p)}){Text(if(p.id==0L)"CREATE PAYCHECK" else "EDIT PAYCHECK")}
            }
            item { Section("ShiftHUD · Recorded work") {
                val e=state.estimate!!
                Text("Paid: ${recordDuration(e.paid)} (${decimalHours(e.paid).stripTrailingZeros().toPlainString()} decimal hours)")
                Text("Estimated base gross: ${e.grossCents?.let{money(it,locale)}?:"Unavailable — missing rate history"}")
                Text("${e.completedSessions} completed sessions · ${e.workedDays} workdays")
                if(e.activeSessions>0)Text("Includes accrued in-progress work. Reconciliation remains incomplete.",color=MaterialTheme.colorScheme.primary)
                Text("Historical effective rates, precise durations, and per-session cent rounding. No automatic overtime premiums.",style=MaterialTheme.typography.bodySmall)
            } }
            val r=state.selected
            if(r==null)item{Text("No paycheck saved for this period. Create a partial record now and fill in facts as they become available.")}
            else {
                item { Section(r.status.label) {
                    Text("Pay date: ${p.payDate?.format(dates)?:"Not entered"}")
                    Text("Reconciled requires employer hours, gross, net, confirmed deductions, pay date, dated deposits, completed work, and zero differences. Optional earnings details, notes, and references are not required.",style=MaterialTheme.typography.bodySmall)
                } }
                item { Section("Employer payroll") {
                    Text("Reported hours: ${p.reportedHours?.toPlainString()?:"Not entered"}")
                    p.reportedHours?.let{Text("Equivalent: ${employerHoursLabel(it)}")}
                    Text("Actual gross: ${moneyOrMissing(p.grossCents)}")
                    Text("Actual net: ${moneyOrMissing(p.netCents)}")
                    p.lines.forEach{line->Text("${line.label}: ${moneyOrMissing(line.cents)}${if(line.kind==PayrollKind.EARNING)" · included in gross" else ""}")}
                    Text("Known deduction subtotal: ${if(p.lines.any{it.kind==PayrollKind.DEDUCTION && it.cents!=null})money(r.knownDeductions,locale) else "Not entered"}")
                    Text("Reported deductions: ${r.deductions?.let{money(it,locale)}?:"Incomplete — confirm all deductions"}")
                } }
                item { Section("Independent differences") {
                    Text("Employer hours − ShiftHUD hours: ${r.hoursDifference?.let{(if(it.signum()>0)"+" else "")+it.stripTrailingZeros().toPlainString()+" h"}?:"Not yet comparable"}")
                    if(r.possibleHoursRounding)Text("This small difference may reflect employer hours rounded to two decimals. It is still shown, not silently reconciled.",style=MaterialTheme.typography.bodySmall)
                    Text("Actual gross − estimated base gross: ${signed(r.grossDifference)}")
                    Text("Actual gross: ${moneyOrMissing(p.grossCents)}\nLess confirmed deductions: ${r.deductions?.let{money(it,locale)}?:"Unknown"}\nCalculated net: ${r.calculatedNet?.let{money(it,locale)}?:"Not yet calculable"}\nActual net: ${moneyOrMissing(p.netCents)}")
                    Text("Calculated net − actual net: ${signed(r.netDifference)}")
                    Text("Actual net − total linked deposits: ${signed(r.depositDifference)}")
                    Text("Differences can have multiple causes. ShiftHUD does not infer taxes, adjust timestamps, or assume deposits equal net pay.",style=MaterialTheme.typography.bodySmall)
                } }
                item { Section("Linked bank deposits") {
                    if(p.deposits.isEmpty())Text("No deposits entered.")
                    p.deposits.forEachIndexed{index,d->Text("Deposit ${index+1} · ${d.date?.format(dates)?:"Date unknown"}\n${moneyOrMissing(d.cents)}")}
                    Text("Total linked deposits: ${r.deposits?.let{money(it,locale)}?:"Incomplete"}")
                    if(p.deposits.any{it.cents!=null} && r.deposits==null)Text("Known subtotal: ${money(r.knownDeposits,locale)}")
                } }
                if(p.reference.isNotBlank()||p.notes.isNotBlank())item{Section("Reference / notes"){if(p.reference.isNotBlank())Text(p.reference);if(p.notes.isNotBlank())Text(p.notes)}}
            }
            item { Text("PAYCHECK HISTORY",style=MaterialTheme.typography.titleMedium) }
            if(state.history.isEmpty())item{Text("No paycheck records yet.")}
            items(state.history,key={it.paycheck.id}) { row ->
                OutlinedCard(onClick={vm.select(row.paycheck.periodStart,row.paycheck.periodEnd);scope.launch{list.scrollToItem(0)}},modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${row.paycheck.periodStart.format(dates)} – ${row.paycheck.periodEnd.format(dates)}",style=MaterialTheme.typography.titleMedium)
                    Text("Pay date: ${row.paycheck.payDate?.format(dates)?:"Not entered"}")
                    Text("Gross: ${moneyOrMissing(row.paycheck.grossCents)} · Net: ${moneyOrMissing(row.paycheck.netCents)}")
                    Text("Deposits: ${row.deposits?.let{money(it,locale)}?:"Incomplete"}")
                    Text(row.status.label,color=MaterialTheme.colorScheme.primary)
                } }
            }
        }
    }
    if(selecting)ScheduleDatePicker(p?.periodStart?:LocalDate.now(),{selecting=false}){selecting=false;vm.week(it)}
    editing?.let{raw->PaycheckEditor(remember(raw){PayrollDraft.restore(raw).model()},busy,vm::save){editing=null}}
}
@Composable private fun Section(title:String,content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.titleMedium);content()}}
}
