package com.shifthud.data.local.entity

import androidx.room.*
import com.shifthud.domain.payroll.*
import java.time.LocalDate

@Entity(tableName="paychecks",indices=[Index(value=["periodStart","periodEnd"],unique=true)])
data class PaycheckEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val periodStart:Long,val periodEnd:Long,val payDate:Long?,val reportedHours:String?,val grossCents:Long?,val netCents:Long?,val deductionsComplete:Boolean,val notes:String,val reference:String,val revision:Long)
@Entity(tableName="payroll_lines",foreignKeys=[ForeignKey(entity=PaycheckEntity::class,parentColumns=["id"],childColumns=["paycheckId"],onDelete=ForeignKey.CASCADE)],indices=[Index("paycheckId")])
data class PayrollLineEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val paycheckId:Long,val kind:String,val label:String,val cents:Long?)
@Entity(tableName="payroll_deposits",foreignKeys=[ForeignKey(entity=PaycheckEntity::class,parentColumns=["id"],childColumns=["paycheckId"],onDelete=ForeignKey.CASCADE)],indices=[Index("paycheckId")])
data class PayrollDepositEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val paycheckId:Long,val date:Long?,val cents:Long?)
data class PaycheckRelations(@Embedded val paycheck:PaycheckEntity,
    @Relation(parentColumn="id",entityColumn="paycheckId") val lines:List<PayrollLineEntity>,
    @Relation(parentColumn="id",entityColumn="paycheckId") val deposits:List<PayrollDepositEntity>) {
    fun model() = paycheck.let { p -> Paycheck(p.id,LocalDate.ofEpochDay(p.periodStart),LocalDate.ofEpochDay(p.periodEnd),p.payDate?.let(LocalDate::ofEpochDay),p.reportedHours?.toBigDecimal(),p.grossCents,p.netCents,p.deductionsComplete,p.notes,p.reference,p.revision,
        lines.sortedBy{it.id}.map{PayrollLine(it.id,PayrollKind.valueOf(it.kind),it.label,it.cents)},deposits.sortedBy{it.id}.map{PayrollDeposit(it.id,it.date?.let(LocalDate::ofEpochDay),it.cents)}) }
}
fun Paycheck.entity(revision:Long) = PaycheckEntity(id,periodStart.toEpochDay(),periodEnd.toEpochDay(),payDate?.toEpochDay(),reportedHours?.toPlainString(),grossCents,netCents,deductionsComplete,notes.trim(),reference.trim(),revision)
