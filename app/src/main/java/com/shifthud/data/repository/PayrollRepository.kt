package com.shifthud.data.repository

import com.shifthud.backup.guardedTransaction as withTransaction
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.*
import com.shifthud.domain.payroll.*
import kotlinx.coroutines.flow.map

/** Payroll facts have their own transaction boundary; no session/rate/notification writes. */
class PayrollRepository(private val db:ShiftDatabase) {
    private val dao=db.payroll()
    val paychecks=dao.observeAll().map { it.map(PaycheckRelations::model) }
    suspend fun save(value:Paycheck):Long {
        validatePaycheck(value)
        return db.withTransaction {
            val previous=if(value.id==0L)null else checkNotNull(dao.get(value.id)){"Paycheck no longer exists. Reopen it."}
            check(previous==null || previous.paycheck.revision==value.revision){"Paycheck changed. Reopen it before editing."}
            val duplicate=dao.periodId(value.periodStart.toEpochDay(),value.periodEnd.toEpochDay())
            require(duplicate==null || duplicate==value.id){"A paycheck already exists for this work period. Open it from history."}
            val oldLines=previous?.lines.orEmpty().map{it.id}.toSet()
            val oldDeposits=previous?.deposits.orEmpty().map{it.id}.toSet()
            require(value.lines.all{it.id<=0 || it.id in oldLines} && value.deposits.all{it.id<=0 || it.id in oldDeposits}) { "A payroll item belongs to another record." }
            val id=if(previous==null)dao.insert(value.entity(1)) else {dao.update(value.entity(value.revision+1));value.id}
            val lines=value.lines.map{it.id}.filter{it>0}
            if(lines.isEmpty())dao.clearLines(id) else dao.removeLines(id,lines)
            val deposits=value.deposits.map{it.id}.filter{it>0}
            if(deposits.isEmpty())dao.clearDeposits(id) else dao.removeDeposits(id,deposits)
            value.lines.forEach{dao.line(PayrollLineEntity(it.id.coerceAtLeast(0),id,it.kind.name,it.label.trim(),it.cents))}
            value.deposits.forEach{dao.deposit(PayrollDepositEntity(it.id.coerceAtLeast(0),id,it.date?.toEpochDay(),it.cents))}
            id
        }
    }
}
