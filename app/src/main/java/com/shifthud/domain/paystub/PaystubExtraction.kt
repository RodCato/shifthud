package com.shifthud.domain.paystub

import com.shifthud.domain.payroll.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Suggestions only. Unknown/ambiguous values stay null; summary rows are never payroll components. */
data class PaystubExtraction(
    val start:LocalDate?, val end:LocalDate?, val payDate:LocalDate?, val regular:BigDecimal?, val overtime:BigDecimal?,
    val gross:Long?, val reportedNet:Long?, val lines:List<PayrollLine>, val deposits:List<PayrollDeposit>,
    val taxTotal:Long?, val deductionTotal:Long?, val warnings:List<String>, val source:String,
) {
    val calculatedNet:Long? get() {
        val g=gross?:return null
        val deductions=lines.filter{it.kind==PayrollKind.DEDUCTION}
        if(deductions.any{it.cents==null})return null
        val all=deductions.sumOf{it.cents!!}
        val taxes=deductions.filter{isTaxLabel(it.label)}.sumOf{it.cents!!}
        // A deductions summary may include taxes or be a separate non-tax subtotal.
        // Derive only when component sums resolve that ambiguity; never add summaries twice.
        val consistent=when {
            deductionTotal!=null -> deductionTotal==all && (taxTotal==null || taxTotal==taxes) ||
                taxTotal==taxes && deductionTotal==all-taxes
            taxTotal!=null -> taxTotal==taxes && all==taxes
            else -> false
        }
        return if(consistent)g-all else null
    }
    fun draft(fallbackStart:LocalDate,fallbackEnd:LocalDate)=Paycheck(periodStart=start?:fallbackStart,periodEnd=end?:fallbackEnd,
        payDate=payDate,grossCents=gross,netCents=reportedNet,lines=lines,deposits=deposits)
}
private fun isTaxLabel(label:String) = listOf("tax","withholding").any{it in label.lowercase()}
private val moneyToken=Regex("(?<![\\w.,])(?:-?\\$?\\d{1,3}(?:,\\d{3})+|-?\\$?\\d+)\\.\\d{2}(?![\\w.,])")
private val dateToken=Regex("\\b(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4})\\b")
private fun date(value:String)=runCatching{LocalDate.parse(value,if('-' in value)DateTimeFormatter.ISO_LOCAL_DATE else DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT))}.getOrNull()
fun extractPaystub(text:String):PaystubExtraction {
    val source=text.take(20000)
    val warnings=mutableListOf("OCR may miss cropped fields or confuse current and year-to-date columns. Review every value against your paystub.")
    val dates=mutableMapOf<String,MutableList<LocalDate>>()
    val values=mutableMapOf<String,MutableList<Long>>()
    val hours=mutableMapOf<String,MutableList<BigDecimal>>()
    val components=mutableListOf<PayrollLine>();val deposits=mutableListOf<PayrollDeposit>()
    var section=""
    var uncertainColumns=false
    fun amount(key:String,v:Long){values.getOrPut(key){mutableListOf()}+=v}
    for(raw in source.lines().take(500)) {
        val line=raw.trim();if(line.isEmpty())continue
        val lower=line.lowercase()
        if(lower in listOf("taxes","deductions","earnings","direct deposits","direct deposit")){section=if(lower.startsWith("direct deposit"))"direct deposits" else lower;continue}
        if("ytd" in lower || "year to date" in lower || "year-to-date" in lower){warnings+="Year-to-date or mixed-column row needs manual review: $line";uncertainColumns=true;continue}
        val ds=dateToken.findAll(line).mapNotNull{date(it.value)}.toList()
        if("pay period" in lower || "period start" in lower || "period end" in lower || "pay date" in lower || "payment date" in lower) {
            when {
                "pay date" in lower || "payment date" in lower -> if(ds.size==1)dates.getOrPut("pay"){mutableListOf()}+=ds.single() else warnings+="Uncertain pay date: $line"
                ds.size==2 -> {dates.getOrPut("start"){mutableListOf()}+=ds[0];dates.getOrPut("end"){mutableListOf()}+=ds[1]}
                ds.size==1 && "start" in lower -> dates.getOrPut("start"){mutableListOf()}+=ds.single()
                ds.size==1 && "end" in lower -> dates.getOrPut("end"){mutableListOf()}+=ds.single()
                else -> warnings+="Uncertain period: $line"
            };continue
        }
        if(uncertainColumns){warnings+="Column association uncertain; enter current value manually: $line";continue}
        if(("regular" in lower || "overtime" in lower) && "hours" in lower) {
            val tokens=Regex("(?<![\\w.])\\d+(?:\\.\\d{1,6})?(?![\\w.])").findAll(line).map{it.value}.toList()
            if(tokens.size==1)runCatching{parsePayrollHours(tokens.single())}.getOrNull()?.let{hours.getOrPut(if("overtime" in lower)"ot" else "regular"){mutableListOf()}+=it}
            else warnings+="Ambiguous hours (hours/rate/earnings columns): $line"
            continue
        }
        val matches=moneyToken.findAll(line).toList()
        if(matches.size!=1 || matches.single().range.last!=line.lastIndex) {
            if(line.any(Char::isDigit) || listOf("gross","net","tax","deduct","deposit","hours").any{it in lower})warnings+="Not auto-filled (missing or ambiguous amount): $line"
            continue
        }
        val match=matches.single()
        val label=line.substring(0,match.range.first).trim().trimEnd(':','$').trim()
        if(label.isEmpty()){warnings+="Unlabeled amount ignored: $line";continue}
        val cents=runCatching{parsePayrollCents(match.value.replace("$","").replace(",",""))}.getOrNull()
        if(cents==null){warnings+="Invalid amount: $line";continue}
        val key=label.lowercase()
        when {
            "total" in key && "tax" in key -> amount("tax",cents)
            "total" in key && "deduct" in key -> amount("deduct",cents)
            "gross" in key || key=="total earnings" -> amount("gross",cents)
            "net pay" in key || "net earnings" in key -> amount("net",cents)
            "total" in key -> warnings+="Summary excluded from components: $line"
            "direct deposit" in key || (section=="direct deposits") -> deposits+=PayrollDeposit(cents=cents)
            "tax" in key || "withholding" in key || "deduction" in key || section in listOf("taxes","deductions") -> components+=PayrollLine(kind=PayrollKind.DEDUCTION,label=label,cents=cents)
            section=="earnings" || "bonus" in key || "adjustment" in key -> components+=PayrollLine(kind=PayrollKind.EARNING,label=label,cents=cents)
            else -> warnings+="Unclassified row; add manually if needed: $line"
        }
    }
    fun value(key:String):Long?=values[key]?.let{if(it.size==1)it.single() else {warnings+="Multiple $key values found; choose the current value manually.";null}}
    fun day(key:String)=dates[key]?.let{if(it.size==1)it.single() else {warnings+="Multiple $key dates found; choose manually.";null}}
    fun hour(key:String)=hours[key]?.let{if(it.size==1)it.single() else {warnings+="Multiple $key hours found; choose manually.";null}}
    val repeated=components.groupBy{it.kind to it.label}.filterValues{it.size>1}.keys
    val lines=components.filter{(it.kind to it.label) !in repeated}
    if(repeated.isNotEmpty())warnings+="Repeated component labels excluded; review source and add the correct current rows."
    val distinctDeposits=if(deposits.map{it.cents}.distinct().size!=deposits.size){warnings+="Equal deposit amounts may be duplicates; confirm each deposit or remove repeated rows.";deposits}else deposits
    val start=day("start");val end=day("end");val pay=day("pay")
    val gross=value("gross");val net=value("net");val tax=value("tax");val deductions=value("deduct")
    val regular=hour("regular");val ot=hour("ot")
    if(start==null || end==null)warnings+="Work period not fully recognized; editor dates start from your selected period and must be confirmed."
    if(gross==null)warnings+="Gross not recognized."
    if(net==null)warnings+="No explicit net-pay field recognized. Calculated net is a suggestion, never reported net."
    if(tax!=null && lines.filter{it.kind==PayrollKind.DEDUCTION && ("tax" in it.label.lowercase() || "withholding" in it.label.lowercase())}.sumOf{it.cents?:0}!=tax)warnings+="Tax total does not match recognized tax components."
    if(deductions!=null && lines.filter{it.kind==PayrollKind.DEDUCTION}.sumOf{it.cents?:0}!=deductions && !(tax!=null && lines.filter{it.kind==PayrollKind.DEDUCTION && !isTaxLabel(it.label)}.sumOf{it.cents?:0}==deductions))warnings+="Deduction total does not match recognized components."
    return PaystubExtraction(start,end,pay,regular,ot,gross,net,lines,distinctDeposits,tax,deductions,warnings,source)
}
/** Recomputed after edits. No missing component is silently replaced by zero. */
fun paystubArithmetic(p:Paycheck):List<String> = buildList {
    val deductions=p.lines.filter{it.kind==PayrollKind.DEDUCTION}
    if(p.grossCents!=null && p.netCents!=null && (p.deductionsComplete || deductions.isNotEmpty()) && deductions.all{it.cents!=null}) {
        val delta=p.grossCents-deductions.sumOf{it.cents!!}-p.netCents
        if(delta!=0L)add("Gross − confirmed deductions − net differs by ${centsInput(delta)} dollars.")
    }
    if(p.netCents!=null && p.deposits.isNotEmpty() && p.deposits.all{it.cents!=null}) {
        val delta=p.netCents-p.deposits.sumOf{it.cents!!}
        if(delta!=0L)add("Net − linked deposits differs by ${centsInput(delta)} dollars.")
    }
    if(p.lines.groupBy{it.kind to it.label.trim().lowercase()}.any{it.value.size>1})add("Repeated payroll labels: review and remove duplicate rows.")
}
