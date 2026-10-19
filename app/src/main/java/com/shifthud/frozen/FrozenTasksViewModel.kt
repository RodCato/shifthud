package com.shifthud.frozen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class FrozenTasksViewModel(private val store:FrozenTaskStore):ViewModel() {
    private val _tasks=MutableStateFlow<List<FrozenTask>?>(null)
    val tasks=_tasks.asStateFlow()
    private val _error=MutableStateFlow<String?>(null)
    val error=_error.asStateFlow()
    private val _busy=MutableStateFlow(false)
    val busy=_busy.asStateFlow()
    init { viewModelScope.launch { try { store.initialize();store.tasks.collect { _tasks.value=it } }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { _error.value="Frozen Tasks could not load. Your saved data has not been replaced. ${e.message}" }
    } }
    fun clearError() { _error.value=null }
    fun state(id:String,state:FrozenState)=write { store.setState(id,state) }
    fun save(id:String?,title:String,description:String,notes:String,done:()->Unit)=write {
        if(id==null)store.add(title,description,notes) else store.saveDetails(id,title,description,notes)
        done()
    }
    private fun write(block:suspend ()->Unit) {
        if(_busy.value)return
        _busy.value=true
        viewModelScope.launch { try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { _error.value=e.message ?: "Task was not saved. Try again." }
            finally { _busy.value=false }
        }
    }
}
