package com.shifthud.ui.quickfind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shifthud.data.repository.QuickFindRepository
import com.shifthud.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class QuickFindState(val items: List<QuickFindItem> = emptyList(), val query: String = "", val results: QuickFindResults = QuickFindResults(), val loaded: Boolean = false, val guide: List<AisleGuideEntry> = emptyList(), val guideMatches: List<AisleGuideEntry> = emptyList())
class QuickFindViewModel(private val repository: QuickFindRepository) : ViewModel() {
    val query = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val state = combine(repository.items, repository.guide, query) { items, guide, query -> QuickFindState(items, query, searchQuickFind(items, query), true, searchAisleGuide(guide, ""), searchAisleGuide(guide, query)) }
        .catch { _error.value = "Could not load saved items. Reopen Quick Find to try again." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QuickFindState())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    fun clearError() { _error.value = null }
    fun save(id: Long?, name: String, aisle: String, note: String, aliases: String, favorite: Boolean, result: (Throwable?) -> Unit) = action {
        try { repository.save(id,name,aisle,note,aliases,favorite); result(null) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { result(e) }
    }
    fun saveGuide(id: Long?, aisle: String, categories: String, result: (String?) -> Unit) = action {
        try { repository.saveGuide(id,aisle,categories);result(null) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { result(e.message ?: "Could not save aisle guide.") }
    }
    fun deleteGuide(id: Long, done: () -> Unit) = action { repository.deleteGuide(id);done() }
    fun select(id: Long) = action { repository.select(id) }
    fun favorite(id: Long, favorite: Boolean) = action { repository.favorite(id, favorite) }
    fun delete(id: Long, done: () -> Unit) = action { repository.delete(id); done() }
    private fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Could not save. Try again." }
            finally { _busy.value = false }
        }
    }
}
