package com.shifthud.ui

import androidx.lifecycle.*
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import com.shifthud.domain.pay.*

data class PayData(val rates: List<PayRate> = emptyList(), val sessions: List<WorkSession> = emptyList(), val error: String? = null, val loaded: Boolean = false)

data class ShiftUiState(val schedule: List<ScheduledShift> = emptyList(), val session: WorkSession? = null, val threshold: Int = 360, val now: Instant = Instant.now(), val loaded: Boolean = false, val warningOffsets: Set<Int> = com.shifthud.notification.DEFAULT_WARNING_OFFSETS, val pay: PayData = PayData())
data class CalendarData(val month: YearMonth, val schedule: List<ScheduledShift> = emptyList(), val sessions: List<WorkSession> = emptyList(), val loaded: Boolean = false, val error: String? = null)
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftViewModel(private val app: ShiftHudApplication) : ViewModel() {
    val engine = app.engine
    private val ticks = flow { while (true) { emit(Instant.now()); delay(1000) } }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)
    private val calendarMonth = MutableStateFlow(YearMonth.now())
    private val localDay = ticks.map { it.atZone(ZoneId.systemDefault()).toLocalDate() to ZoneId.systemDefault() }.distinctUntilChanged()
    private val weeklySessions = localDay.flatMapLatest { (date, zone) ->
        val week = workWeekFor(date)
        app.repository.sessionsBetween(week.start, week.endInclusive.plusDays(1), zone)
    }
    val calendar = combine(calendarMonth, localDay) { month, day -> month to day.second }.distinctUntilChanged().flatMapLatest { (month, zone) ->
        val range = com.shifthud.domain.calendar.calendarRange(month)
        combine(app.repository.scheduleBetween(range.start, range.endExclusive), app.repository.sessionsBetween(range.start, range.endExclusive, zone)) { schedule, sessions ->
            CalendarData(month, schedule, sessions, true)
        }.onStart { emit(CalendarData(month)) }.catch { emit(CalendarData(month, error = "Calendar unavailable. Please reopen Dashboard to retry.")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarData(YearMonth.now()))
    fun calendarMonth(month: YearMonth) { calendarMonth.value = month }
    fun record(id: Long) = app.repository.session(id)
    private val payData = combine(app.payRates.rates, weeklySessions) { rates, sessions -> PayData(rates, sessions, loaded = true) }
        .catch { emit(PayData(error = "Pay estimates unavailable. Could not read pay-rate history or time records.")) }
    private val shiftState = combine(app.repository.schedule, app.repository.latestSession, app.preferences.warningSettings, ticks) { schedule, session, settings, now -> ShiftUiState(schedule, session, settings.threshold, now, true, settings.offsets) }
    val state = combine(shiftState, payData) { shift, pay -> shift.copy(pay = pay) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShiftUiState())
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    fun clearError() { _error.value = null }
    private fun perform(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Could not save. Please try again." }
            finally { _busy.value = false }
        }
    }
    fun saveRate(rate: PayRate, done: () -> Unit) = perform { app.payRates.save(rate); done() }
    fun addHistorical(input: com.shifthud.domain.usecase.HistoricalShiftInput, zone: ZoneId, result: (Long?, Exception?) -> Unit) = perform {
        try { result(app.repository.addHistorical(input, zone), null) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { result(null, e) }
    }
    fun deleteHistorical(session: WorkSession, done: () -> Unit) = perform { app.repository.deleteHistorical(session); done() }
    fun clockIn() = perform { app.repository.clockIn() }
    fun startLunch(s: WorkSession) = perform { app.repository.transition(s.id, s.state, engine::startLunch) }
    fun endLunch(s: WorkSession) = perform { app.repository.transition(s.id, s.state, engine::endLunch) }
    fun clockOut(s: WorkSession) = perform { app.repository.transition(s.id, s.state, engine::clockOut) }
    fun save(s: ScheduledShift, done: () -> Unit) = perform { app.repository.save(s); done() }
    fun delete(id: Long, done: () -> Unit) = perform { app.repository.delete(id); done() }
    // Each settings tap is queued; unrelated refresh work must not silently discard a toggle.
    fun warning(offset: Int, enabled: Boolean) = viewModelScope.launch {
        try { app.preferences.setWarningEnabled(offset, enabled) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _error.value = e.message ?: "Could not save warning." }
    }
    fun weeklyTarget(settings: com.shifthud.domain.weekly.WeeklyTargetSettings) = perform { app.preferences.setWeeklyTarget(settings) }
    fun shiftEnd(settings: com.shifthud.notification.ShiftEndSettings) = perform { app.preferences.setShiftEnd(settings) }
    fun snoozeMinutes(minutes: Int) = perform { app.preferences.setSnoozeMinutes(minutes) }
    fun autoLunch(enabled: Boolean, minutes: Int) = perform { app.preferences.setAutoLunch(enabled, minutes) }
    fun correctTime(session: WorkSession, event: com.shifthud.domain.usecase.TimeEvent, value: Instant, result: (String?) -> Unit) = perform {
        try { app.repository.correct(session, event, value); result(null) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { result(e.message ?: "Could not save the correction.") }
    }
    fun threshold(minutes: Int, done: () -> Unit) = perform { app.preferences.setLunchThreshold(minutes); done() }
}
