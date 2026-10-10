package com.shifthud.ui.importing

import androidx.lifecycle.*
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.importing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class HistoricalImportState(val rows: List<BackfillReview> = emptyList(), val zone: ZoneId = ZoneId.systemDefault(), val selected: Set<LocalDate> = emptySet(), val error: String? = null)
class HistoricalImportViewModel(private val repository: ShiftRepository, private val saved: SavedStateHandle): ViewModel() {
    val zone = saved.getStateFlow("zone",ZoneId.systemDefault().id)
    private val selected = saved.getStateFlow("selected",PUBLIX_BACKFILL.joinToString("|"){it.date.toString()})
    val message = saved.getStateFlow<String?>("message",null)
    private val loading = MutableStateFlow(false)
    val busy = loading.asStateFlow()
    val state = combine(repository.sessions,zone,selected) { sessions, zoneId, dates ->
        val tz=ZoneId.of(zoneId)
        HistoricalImportState(PUBLIX_BACKFILL.map{reviewBackfill(it,sessions,tz,Instant.now())},tz,dates.split('|').filter{it.isNotEmpty()}.map(LocalDate::parse).toSet())
    }.catch{emit(HistoricalImportState(error="Could not load historical records. Reopen this screen to retry."))}
        .flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),HistoricalImportState())
    fun zone(value: String): String? = try {
        val next=ZoneId.of(value.trim());saved["zone"]=next.id;saved["message"]=null;null
    } catch(_:DateTimeException){"Enter a valid time zone, such as America/New_York or America/Chicago."}
    fun select(date: LocalDate, checked: Boolean) {
        val dates=selected.value.split('|').filter{it.isNotEmpty()}.toMutableSet()
        if(checked)dates+=date.toString() else dates-=date.toString()
        saved["selected"]=dates.joinToString("|")
    }
    fun import(dates:Set<LocalDate>,zone:ZoneId) {
        if(loading.value)return
        loading.value=true
        viewModelScope.launch {
            try { saved["message"]=repository.importBackfill(dates,zone).message }
            catch(e:CancellationException){throw e}
            catch(e:Exception){saved["message"]="Import could not finish: ${e.message ?: "Reopen and review records before retrying."}"}
            finally { loading.value=false }
        }
    }
}
