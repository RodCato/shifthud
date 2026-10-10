package com.shifthud.ui.analytics

import androidx.lifecycle.*
import com.shifthud.ShiftHudApplication
import com.shifthud.data.repository.AnalyticsRepository
import com.shifthud.domain.analytics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class AnalyticsState(val period: AnalyticsPeriod, val result: WorkAnalytics? = null, val error: String? = null)
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(app: ShiftHudApplication, private val saved: SavedStateHandle): ViewModel() {
    val weekly = saved.getStateFlow("weekly", true)
    val week = saved.getStateFlow("week", AnalyticsPeriod.week(LocalDate.now()).start.toString())
    val month = saved.getStateFlow("month", YearMonth.now().toString())
    private val refresh = MutableStateFlow(0)
    private val period = combine(weekly, week, month) { isWeek, weekStart, monthStart ->
        if (isWeek) AnalyticsPeriod.week(LocalDate.parse(weekStart)) else AnalyticsPeriod.month(YearMonth.parse(monthStart))
    }
    private val ticks = flow { while (true) { emit(Instant.now()); delay(1000) } }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay=1)
    private val repository = AnalyticsRepository(app.repository, app.payRates.rates, app.preferences.weeklyTargetSettings, app.engine)
    val state = combine(period, ticks.map { ZoneId.systemDefault() }.distinctUntilChanged(), refresh) { p, zone, _ -> p to zone }
        .flatMapLatest { (p, zone) -> repository.observe(p, zone, ticks).map { AnalyticsState(p, it) }
            .onStart { emit(AnalyticsState(p)) }.catch { emit(AnalyticsState(p, error="Analytics unavailable. Please retry.")) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsState(AnalyticsPeriod.week(LocalDate.now())))
    fun mode(weekly: Boolean) { saved["weekly"] = weekly }
    fun move(amount: Long) { if (weekly.value) saved["week"] = LocalDate.parse(week.value).plusWeeks(amount).toString() else saved["month"] = YearMonth.parse(month.value).plusMonths(amount).toString() }
    fun current() { if (weekly.value) saved["week"] = AnalyticsPeriod.week(LocalDate.now()).start.toString() else saved["month"] = YearMonth.now().toString() }
    fun retry() { refresh.value++ }
}
