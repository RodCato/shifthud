package com.shifthud.ui.payroll

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shifthud.domain.payroll.*
import com.shifthud.domain.paystub.*
import com.shifthud.ui.schedule.ScheduleDatePicker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun PaycheckEditor(initial:Paycheck,busy:Boolean,save:(Paycheck,(String?)->Unit)->Unit,review:PaystubExtraction?=null,rates:List<com.shifthud.domain.pay.PayRate> = emptyList(),dismiss:()->Unit) {
    var draft by rememberSaveable(initial.id,initial.revision,stateSaver=PayrollDraft.saver){mutableStateOf(PayrollDraft.from(initial))}
    var error by rememberSaveable{mutableStateOf<String?>(null)}
    var regular by rememberSaveable{mutableStateOf(review?.regular?.toPlainString().orEmpty())}
    var overtime by rememberSaveable{mutableStateOf(review?.overtime?.toPlainString().orEmpty())}
    var calculatedNetAccepted by rememberSaveable{mutableStateOf(false)}
    var confirmed by rememberSaveable{mutableStateOf(false)}
    var detailsExpanded by rememberSaveable{mutableStateOf(false)}
    var reviewedWarnings by rememberSaveable{mutableStateOf<String?>(null)}
    val arithmetic=runCatching{paystubArithmetic(draft.model()) + if(review!=null)(paystubRateCheck(draft.model(),parsePayrollHours(overtime),rates)+paystubSummaryCheck(draft.model(),review)) else emptyList()}.getOrDefault(emptyList())
    val warningKey=draft.serialize()
    LaunchedEffect(warningKey){confirmed=false}
    Dialog(onDismissRequest={if(!busy)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp)) {
            Text(if(initial.id==0L)"New paycheck" else "Edit paycheck",style=MaterialTheme.typography.headlineSmall)
            Text("Blank means unknown; enter 0 for a known zero.",style=MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
                if(review!=null)item {
                    Text("PAYSTUB IMPORT REVIEW",style=MaterialTheme.typography.titleLarge)
                    Text("Review required · Edit extracted values below. Nothing is saved until you confirm.")
                    Text("Period: ${draft.start} – ${draft.end} · Pay date: ${draft.payDate.ifBlank{"Unknown"}}")
                    Text("Gross: $${draft.gross.ifBlank{"Unknown"}} · Taxes: ${centsInput(review.taxTotal).ifBlank{"Unknown"}}")
                    Text("Deposits: ${draft.deposits.joinToString(" + "){it.amount.ifBlank{"Unknown"}}.ifBlank{"Unknown"}}")
                    review.warnings.forEach{Text("• $it",style=MaterialTheme.typography.bodySmall)}
                    TextButton(onClick={detailsExpanded=!detailsExpanded}){Text(if(detailsExpanded)"HIDE OCR DETAILS" else "EXPAND OCR DETAILS")}
                    if(detailsExpanded) {
                        Text(review.source,style=MaterialTheme.typography.bodySmall)
                        review.confidence.forEach{(field,confidence)->Text("$field: ${confidence.name.replace('_',' ')}",style=MaterialTheme.typography.bodySmall)}
                    }
                    Text("Regular and overtime hours",style=MaterialTheme.typography.titleMedium)
                    NumericField("Regular hours",regular,!busy){regular=it;confirmed=false}
                    NumericField("Overtime hours (if provided)",overtime,!busy){overtime=it;confirmed=false}
                    Text("Use only the entered components if they represent all paid hours. Blank does not mean zero. No overtime premium is calculated.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled=!busy,onClick={try {
                        val parts=listOfNotNull(parsePayrollHours(regular),parsePayrollHours(overtime))
                        require(parts.isNotEmpty()){ "Enter recognized hours first." }
                        draft=draft.copy(hours=parts.reduce(java.math.BigDecimal::add).toPlainString());confirmed=false
                    }catch(e:Exception){error=e.message}}){Text("USE ENTERED HOURS TOTAL")}
                    review.calculatedNet?.takeIf{review.reportedNet==null}?.let{suggested->
                        Text("Suggested net: $${centsInput(suggested)} (calculated, not reported)")
                        OutlinedButton(enabled=!busy,onClick={draft=draft.copy(net=centsInput(suggested));calculatedNetAccepted=true;confirmed=false}){Text("ACCEPT CALCULATED NET")}
                    }
                    Text("Recognized total taxes: ${centsInput(review.taxTotal).ifBlank{"Unknown"}} · Total deductions: ${centsInput(review.deductionTotal).ifBlank{"Unknown"}}. Summary rows are not added as deductions.",style=MaterialTheme.typography.bodySmall)
                }
                item { Text("Work period",style=MaterialTheme.typography.titleMedium);Text("Defaults to Saturday–Friday. Custom periods are supported; dates are inclusive.",style=MaterialTheme.typography.bodySmall) }
                item { PayrollDateField("Period start",draft.start,false,!busy){draft=draft.copy(start=it)} }
                item { PayrollDateField("Period end",draft.end,false,!busy){draft=draft.copy(end=it)} }
                item { PayrollDateField("Pay date",draft.payDate,true,!busy){draft=draft.copy(payDate=it)} }
                item { Text("Employer payroll",style=MaterialTheme.typography.titleMedium) }
                item {
                    NumericField("Reported paid hours (decimal)",draft.hours,!busy){draft=draft.copy(hours=it)}
                    Text("39.15 hours = 39h 9m, not 39h 15m.",style=MaterialTheme.typography.bodySmall)
                    runCatching{parsePayrollHours(draft.hours)?.let(::employerHoursLabel)}.getOrNull()?.let{Text("Equivalent: $it",style=MaterialTheme.typography.bodySmall)}
                }
                item { NumericField("Actual gross pay ($)",draft.gross,!busy){draft=draft.copy(gross=it)} }
                item { NumericField("Actual net pay ($)",draft.net,!busy){draft=draft.copy(net=it);calculatedNetAccepted=false} }
                item { Text("Deductions and earnings",style=MaterialTheme.typography.titleMedium);Text("Deductions reduce gross; negative amounts represent refunds. Earnings/adjustments are optional details already included in actual gross and are never added twice.",style=MaterialTheme.typography.bodySmall) }
                items(draft.lines,key={"line${it.id}"}) { line ->
                    OutlinedCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(line.label,{text->draft=draft.copy(lines=draft.lines.map{if(it.id==line.id)it.copy(label=text) else it})},label={Text(if(line.kind==PayrollKind.DEDUCTION)"Deduction category" else "Earnings / adjustment category")},enabled=!busy,modifier=Modifier.fillMaxWidth())
                        NumericField("${line.label} ($)",line.amount,!busy){text->draft=draft.copy(lines=draft.lines.map{if(it.id==line.id)it.copy(amount=text) else it})}
                        TextButton(enabled=!busy,onClick={draft=draft.copy(lines=draft.lines.filterNot{it.id==line.id},complete=if(line.kind==PayrollKind.DEDUCTION)false else draft.complete)}){Text("REMOVE CATEGORY")}
                    } }
                }
                item { OutlinedButton(enabled=!busy,onClick={draft=draft.copy(lines=draft.lines+LineDraft(draft.nextId,PayrollKind.DEDUCTION,"Additional deduction",""),nextId=draft.nextId-1,complete=false)}){Text("ADD DEDUCTION")} }
                item { OutlinedButton(enabled=!busy,onClick={draft=draft.copy(lines=draft.lines+LineDraft(draft.nextId,PayrollKind.EARNING,"Other earnings / adjustments",""),nextId=draft.nextId-1)}){Text("ADD EARNINGS / ADJUSTMENT")} }
                item {
                    Row { Checkbox(draft.complete,{draft=draft.copy(complete=it)},enabled=!busy);Text("All deductions are accounted for") }
                    Text("Fill each deduction amount (including 0), or remove categories that do not apply. Checking this with no deduction categories confirms $0 total. Blank deduction amounts keep the record incomplete.",style=MaterialTheme.typography.bodySmall)
                }
                item { Text("Bank deposits",style=MaterialTheme.typography.titleMedium);Text("Record split deposits separately. Signed negative deposits can record a reversal. No bank account details are needed.",style=MaterialTheme.typography.bodySmall) }
                items(draft.deposits,key={"deposit${it.id}"}) { deposit ->
                    OutlinedCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        PayrollDateField("Deposit date",deposit.date,true,!busy){text->draft=draft.copy(deposits=draft.deposits.map{if(it.id==deposit.id)it.copy(date=text) else it})}
                        NumericField("Deposit amount ($)",deposit.amount,!busy){text->draft=draft.copy(deposits=draft.deposits.map{if(it.id==deposit.id)it.copy(amount=text) else it})}
                        TextButton(enabled=!busy,onClick={draft=draft.copy(deposits=draft.deposits.filterNot{it.id==deposit.id})}){Text("REMOVE DEPOSIT")}
                    } }
                }
                item { OutlinedButton(enabled=!busy,onClick={draft=draft.copy(deposits=draft.deposits+DepositDraft(draft.nextId,"",""),nextId=draft.nextId-1)}){Text("ADD DEPOSIT")} }
                item { OutlinedTextField(draft.reference,{draft=draft.copy(reference=it)},label={Text("Paystub reference (optional)")},enabled=!busy,modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(draft.notes,{draft=draft.copy(notes=it)},label={Text("Notes (optional)")},enabled=!busy,modifier=Modifier.fillMaxWidth(),minLines=2) }
            }
            if(review!=null) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(confirmed,{confirmed=it},enabled=!busy)
                    Text("I reviewed the work period, current amounts, missing fields, and OCR uncertainties.",style=MaterialTheme.typography.bodySmall)
                }
                arithmetic.forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                if(arithmetic.isNotEmpty())Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(reviewedWarnings==warningKey,{reviewedWarnings=if(it)warningKey else null});Text("Save with these reviewed differences",style=MaterialTheme.typography.bodySmall)}
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(enabled=!busy,onClick=dismiss){Text("CANCEL")}
                Button(enabled=!busy && (review==null || (confirmed && (arithmetic.isEmpty() || reviewedWarnings==warningKey))),onClick={try{
                    var model=draft.model()
                    if(review!=null) {
                        parsePayrollHours(regular);parsePayrollHours(overtime)
                        val provenance="Paystub image reviewed. Regular hours: ${regular.ifBlank{"not provided"}}; overtime hours: ${overtime.ifBlank{"not provided"}}." + if(calculatedNetAccepted) " Net accepted as a calculated suggestion, not explicitly reported." else ""
                        model=model.copy(notes=listOf(model.notes,provenance).filter{it.isNotBlank()}.joinToString("\n"))
                    }
                    save(model){failure->error=failure;if(failure==null)dismiss()}}catch(e:Exception){error=e.message?:"Check your entries."}}){Text(if(busy)"SAVING…" else "SAVE PAYCHECK")}
            }
        } }
    }
}
@Composable private fun NumericField(label:String,value:String,enabled:Boolean,change:(String)->Unit) {
    OutlinedTextField(value,change,label={Text(label)},enabled=enabled,modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
}
@Composable private fun PayrollDateField(label:String,value:String,optional:Boolean,enabled:Boolean,change:(String)->Unit) {
    var picking by rememberSaveable{mutableStateOf(false)}
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    OutlinedButton(enabled=enabled,onClick={picking=true},modifier=Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) {
        Text(label,style=MaterialTheme.typography.labelMedium)
        Text(value.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))}?:"Not entered · Select date")
    } }
    if(optional && value.isNotBlank())TextButton(enabled=enabled,onClick={change("")}){Text("CLEAR ${label.uppercase()}")}
    if(picking)ScheduleDatePicker(value.takeIf{it.isNotBlank()}?.let(LocalDate::parse)?:LocalDate.now(),{picking=false}){picking=false;change(it.toString())}
}
