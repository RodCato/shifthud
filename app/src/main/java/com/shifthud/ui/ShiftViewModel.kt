package com.shifthud.ui

import androidx.lifecycle.*
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import com.shifthud.domain.pay.*

data class PayData(val rates: List<PayRate> = emptyList(), val sessions: List<WorkSession> = emptyList(), val error: String? = null)

data class ShiftUiState(val schedule: List<ScheduledShift> = emptyList(), val session: WorkSession? = null, val threshold: Int = 360, val now: Instant = Instant.now(), val loaded: Boolean = false, val warningOffsets: Set<Int> = com.shifthud.notification.DEFAULT_WARNING_OFFSETS, val pay: PayData = PayData())
class ShiftViewModel(private val app: ShiftHudApplication) : ViewModel() {
    val engine = app.engine
    private val ticks = flow { while (true) { emit(Instant.now()); delay(1000) } }
    private val payData = combine(app.payRates.rates, app.repository.sessions) { rates, sessions -> PayData(rates, sessions) }
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
    fun snoozeMinutes(minutes: Int) = perform { app.preferences.setSnoozeMinutes(minutes) }
    fun autoLunch(enabled: Boolean, minutes: Int) = perform { app.preferences.setAutoLunch(enabled, minutes) }
    fun correctTime(session: WorkSession, event: com.shifthud.domain.usecase.TimeEvent, value: Instant, result: (String?) -> Unit) = perform {
        try { app.repository.correct(session, event, value); result(null) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { result(e.message ?: "Could not save the correction.") }
    }
    fun threshold(minutes: Int, done: () -> Unit) = perform { app.preferences.setLunchThreshold(minutes); done() }
}
