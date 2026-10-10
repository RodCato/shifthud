package com.shifthud.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.*

/** Common outer lock for persistence and runtime reconciliation; nested calls retain ownership. */
object DataGate {
    @Volatile var blocked=false
    @Volatile private var replacing=false
    @Volatile private var generation=0L
    private val mutex=Mutex()
    private class Owner:AbstractCoroutineContextElement(Key) { companion object Key:CoroutineContext.Key<Owner> }
    suspend fun <T> with(block:suspend ()->T):T {
        check(!blocked){"Restore recovery required. Force-stop and reopen ShiftHUD; do not clear its data."}
        if(currentCoroutineContext()[Owner]!=null)return block()
        val expected=generation
        check(!replacing){"Data replacement is in progress. Try again when it finishes."}
        return mutex.withLock {
            check(!blocked){"Restore recovery required."}
            check(!replacing && generation==expected){"Dataset changed. Reopen this action and try again."}
            withContext(Owner()){block()}
        }
    }
    suspend fun <T> replace(block:suspend ()->T):T=with {
        generation++;replacing=true
        try{block()}finally{generation++;replacing=false}
    }
}
suspend fun <T> RoomDatabase.guardedTransaction(block:suspend ()->T):T=DataGate.with{withTransaction(block)}
suspend fun DataStore<Preferences>.guardedEdit(block:suspend (MutablePreferences)->Unit):Preferences=DataGate.with{edit(block)}
suspend inline fun <T> Mutex.guardedLock(owner:Any?=null,crossinline action:suspend ()->T):T=DataGate.with{withLock(owner){action()}}
