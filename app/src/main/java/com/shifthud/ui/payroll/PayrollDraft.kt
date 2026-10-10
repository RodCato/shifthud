package com.shifthud.ui.payroll

import androidx.compose.runtime.saveable.Saver
import com.shifthud.domain.payroll.*
import org.json.*
import java.time.LocalDate

data class LineDraft(val id:Long,val kind:PayrollKind,val label:String,val amount:String)
data class DepositDraft(val id:Long,val date:String,val amount:String)
data class PayrollDraft(val id:Long,val revision:Long,val start:String,val end:String,val payDate:String,val hours:String,val gross:String,val net:String,val complete:Boolean,val notes:String,val reference:String,val lines:List<LineDraft>,val deposits:List<DepositDraft>,val nextId:Long) {
    fun model()=Paycheck(id,LocalDate.parse(start),LocalDate.parse(end),payDate.takeIf{it.isNotBlank()}?.let(LocalDate::parse),parsePayrollHours(hours),parsePayrollCents(gross),parsePayrollCents(net),complete,notes,reference,revision,
        lines.map{PayrollLine(it.id,it.kind,it.label,parsePayrollCents(it.amount))},deposits.map{PayrollDeposit(it.id,it.date.takeIf{it.isNotBlank()}?.let(LocalDate::parse),parsePayrollCents(it.amount))}).also(::validatePaycheck)
    fun serialize():String = JSONObject().put("id",id).put("revision",revision).put("start",start).put("end",end).put("payDate",payDate).put("hours",hours).put("gross",gross).put("net",net).put("complete",complete).put("notes",notes).put("reference",reference).put("nextId",nextId)
        .put("lines",JSONArray().also{a->lines.forEach{a.put(JSONObject().put("id",it.id).put("kind",it.kind.name).put("label",it.label).put("amount",it.amount))}})
        .put("deposits",JSONArray().also{a->deposits.forEach{a.put(JSONObject().put("id",it.id).put("date",it.date).put("amount",it.amount))}}).toString()
    companion object {
        fun from(p:Paycheck):PayrollDraft { var next=-1L;return PayrollDraft(p.id,p.revision,p.periodStart.toString(),p.periodEnd.toString(),p.payDate?.toString().orEmpty(),p.reportedHours?.toPlainString().orEmpty(),centsInput(p.grossCents),centsInput(p.netCents),p.deductionsComplete,p.notes,p.reference,
            p.lines.map{LineDraft(if(it.id>0)it.id else next--,it.kind,it.label,centsInput(it.cents))},p.deposits.map{DepositDraft(if(it.id>0)it.id else next--,it.date?.toString().orEmpty(),centsInput(it.cents))},next) }
        fun restore(value:String):PayrollDraft { val j=JSONObject(value);val lines=j.getJSONArray("lines");val deposits=j.getJSONArray("deposits")
            return PayrollDraft(j.getLong("id"),j.getLong("revision"),j.getString("start"),j.getString("end"),j.getString("payDate"),j.getString("hours"),j.getString("gross"),j.getString("net"),j.getBoolean("complete"),j.getString("notes"),j.getString("reference"),
                (0 until lines.length()).map{lines.getJSONObject(it).let{l->LineDraft(l.getLong("id"),PayrollKind.valueOf(l.getString("kind")),l.getString("label"),l.getString("amount"))}},
                (0 until deposits.length()).map{deposits.getJSONObject(it).let{d->DepositDraft(d.getLong("id"),d.getString("date"),d.getString("amount"))}},j.getLong("nextId"))
        }
        val saver=Saver<PayrollDraft,String>(save={it.serialize()},restore={restore(it)})
    }
}
