package com.shifthud.data.local.dao

import androidx.room.*
import com.shifthud.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao interface PayrollDao {
    @Transaction @Query("SELECT * FROM paychecks ORDER BY periodEnd DESC, periodStart DESC, id DESC") fun observeAll():Flow<List<PaycheckRelations>>
    @Transaction @Query("SELECT * FROM paychecks WHERE id=:id") suspend fun get(id:Long):PaycheckRelations?
    @Query("SELECT id FROM paychecks WHERE periodStart=:start AND periodEnd=:end") suspend fun periodId(start:Long,end:Long):Long?
    @Insert suspend fun insert(paycheck:PaycheckEntity):Long
    @Update suspend fun update(paycheck:PaycheckEntity)
    @Upsert suspend fun line(line:PayrollLineEntity)
    @Upsert suspend fun deposit(deposit:PayrollDepositEntity)
    @Query("DELETE FROM payroll_lines WHERE paycheckId=:id AND id NOT IN (:keep)") suspend fun removeLines(id:Long,keep:List<Long>)
    @Query("DELETE FROM payroll_deposits WHERE paycheckId=:id AND id NOT IN (:keep)") suspend fun removeDeposits(id:Long,keep:List<Long>)
    @Query("DELETE FROM payroll_lines WHERE paycheckId=:id") suspend fun clearLines(id:Long)
    @Query("DELETE FROM payroll_deposits WHERE paycheckId=:id") suspend fun clearDeposits(id:Long)
}
