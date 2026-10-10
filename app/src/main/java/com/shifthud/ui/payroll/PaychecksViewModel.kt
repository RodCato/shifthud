package com.shifthud.ui.payroll

import androidx.lifecycle.*
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.analytics.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.payroll.*
import com.shifthud.domain.weekly.WeeklyTargetSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class PaychecksState(val draft:Paycheck?=null,val estimate:WorkAnalytics?=null,val history:List<PayrollReconciliation> = emptyList(),val error:String?=null) {
    val selected get() = history.firstOrNull{it.paycheck.periodStart==draft?.periodStart && it.paycheck.periodEnd==draft.periodEnd}
}
@OptIn(ExperimentalCoroutinesApi::class)
class PaychecksViewModel(private val app:ShiftHudApplication,private val saved:SavedStateHandle,initial:LocalDate):ViewModel() {
    private val initialWeek=workWeekFor(initial)
    private val selection=saved.getStateFlow("period","${initialWeek.start}|${initialWeek.endInclusive}")
    private val refresh=MutableStateFlow(0)
    private val ticks=flow{while(true){emit(Instant.now());delay(1000)}}.shareIn(viewModelScope,SharingStarted.WhileSubscribed(5000),replay=1)
    private val estimator=PayEstimator(app.engine)
    val busy=MutableStateFlow(false)
    val state=combine(app.payroll.paychecks,selection,ticks.map{ZoneId.systemDefault()}.distinctUntilChanged(),refresh){checks,period,zone,_->Triple(checks,period,zone)}
        .flatMapLatest{(checks,period,zone)->
            val dates=period.split('|').map(LocalDate::parse)
            val draft=checks.firstOrNull{it.periodStart==dates[0] && it.periodEnd==dates[1]} ?: Paycheck(periodStart=dates[0],periodEnd=dates[1],lines=defaultPayrollLines())
            val start=minOf(draft.periodStart,checks.minOfOrNull{it.periodStart}?:draft.periodStart)
            val end=maxOf(draft.periodEnd,checks.maxOfOrNull{it.periodEnd}?:draft.periodEnd).plusDays(1)
            combine(app.repository.sessionsBetween(start,end,zone),app.payRates.rates,ticks){sessions,rates,now->
                fun estimate(p:Paycheck)=workAnalytics(p.period,sessions,emptyList(),rates,now,zone,estimator,WeeklyTargetSettings())
                PaychecksState(draft,estimate(draft),checks.map{PayrollReconciliation(it,estimate(it))})
            }.onStart{emit(PaychecksState())}.catch{emit(PaychecksState(error="Paychecks unavailable. Please retry."))}
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),PaychecksState())
    fun select(start:LocalDate,end:LocalDate) { saved["period"]="$start|$end" }
    fun week(date:LocalDate) { val w=workWeekFor(date);select(w.start,w.endInclusive) }
    fun retry(){refresh.value++}
    fun save(paycheck:Paycheck,result:(String?)->Unit) {
        if(busy.value)return
        busy.value=true
        viewModelScope.launch {
            try{app.payroll.save(paycheck);select(paycheck.periodStart,paycheck.periodEnd);result(null)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){result(e.message?:"Could not save paycheck.")}
            finally{busy.value=false}
        }
    }
}
