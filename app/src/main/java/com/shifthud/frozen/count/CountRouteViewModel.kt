package com.shifthud.frozen.count

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CountRouteViewModel(private val store:CountRouteStore):ViewModel() {
    private val _data=MutableStateFlow<CountRouteData?>(null);val data=_data.asStateFlow()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    private val _busy=MutableStateFlow(false);val busy=_busy.asStateFlow()
    private var lastKey="";private var lastTap=0L
    init {viewModelScope.launch {try {store.data.collect {_data.value=it}} catch(e:CancellationException) {throw e} catch(_:Exception) {_error.value="Count Route could not load. Saved data has not been reset. Reopen to retry."}}}
    fun clearError() {_error.value=null}
    fun saveMap(data:CountRouteData,map:List<RouteSegment>,done:()->Unit)=write("map") {store.saveMap(data.revision,map);done()}
    fun target(data:CountRouteData,target:Int,done:()->Unit)=write("target") {store.target(data.revision,target);done()}
    fun confirmExistingRoute(data:CountRouteData)=write("confirm-existing") {store.confirmExistingRoute(data.revision,requireNotNull(data.active).number)}
    fun start(data:CountRouteData)=write("start") {store.start(data.revision)}
    fun count(data:CountRouteData,undo:Boolean=false)=write("count:${data.active?.number}:${data.active?.current}:$undo") {
        val c=requireNotNull(data.active);store.count(data.revision,c.number,requireNotNull(c.current),undo)
    }
    fun resolve(data:CountRouteData,state:DoorState,note:String="",done:()->Unit)=write("resolve:${data.active?.number}") {
        val c=requireNotNull(data.active);store.resolve(data.revision,c.number,requireNotNull(c.current),state,note);done()
    }
    fun revisit(data:CountRouteData,door:String)=write("revisit:$door") {store.revisit(data.revision,requireNotNull(data.active).number,door)}
    fun pause(data:CountRouteData)=write("pause") {store.pause(data.revision,requireNotNull(data.active).number)}
    fun resume(data:CountRouteData)=write("resume") {store.resume(data.revision,requireNotNull(data.active).number)}
    fun finish(data:CountRouteData,done:()->Unit)=write("finish") {store.finish(data.revision,requireNotNull(data.active).number);done()}
    fun submission(data:CountRouteData,cycle:Int,session:String,status:Submission,note:String,done:()->Unit)=write("submission") {store.submission(data.revision,cycle,session,status,note);done()}
    private fun write(key:String,block:suspend ()->Unit) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(_busy.value || (lastKey==key && now-lastTap<350))return
        lastKey=key;lastTap=now;_busy.value=true;_error.value=null
        viewModelScope.launch {try {block()} catch(e:CancellationException) {throw e} catch(e:Exception) {_error.value=e.message ?: "Change was not saved. Try again."} finally {_busy.value=false}}
    }
}
