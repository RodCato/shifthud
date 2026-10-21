package com.shifthud.frozen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class FrozenTasksViewModel(private val store:FrozenTaskStore):ViewModel() {
    private val _error=MutableStateFlow<String?>(null)
    private val _tasks=MutableStateFlow<List<FrozenTask>?>(null)
    val tasks=_tasks.asStateFlow()
    val selectedWorkflow=store.selectedWorkflow.stateIn(viewModelScope,SharingStarted.Eagerly, "")
    val truckWorkflows=store.truckWorkflows.catch { _error.value="Truck status could not load. Saved data has not been replaced." }.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    fun startWednesday(date:java.time.LocalDate)=write { store.startWednesday(date) }
    fun truckArrival(date:java.time.LocalDate,arrived:Boolean)=write { store.setTruckArrived(date,arrived) }
    fun select(workflow:String)=write { store.selectWorkflow(workflow) }
    fun start(date:java.time.LocalDate)=write { store.startTuesday(date) }
    fun importProducts(date:java.time.LocalDate, week:java.time.LocalDate, location:String, products:List<EndcapProduct>,done:()->Unit)=write {
        store.importProducts(date,week,location,products);done()
    }
    fun editProduct(id:String,name:String,sku:String,done:()->Unit)=write { store.editProduct(id,name,sku);done() }
    fun removeProduct(id:String)=write { store.removeProduct(id) }
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
