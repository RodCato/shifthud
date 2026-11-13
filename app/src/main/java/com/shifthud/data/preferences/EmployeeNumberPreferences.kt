package com.shifthud.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.shifthud.backup.guardedEdit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map

// Local-only settings, deliberately outside exportable payroll/planner preferences.
private val Context.employeeNumberStore by preferencesDataStore(name="employee_number")
class EmployeeNumberPreferences(private val store:DataStore<Preferences>,private val redraw:suspend ()->Unit={}) {
    constructor(context:Context,redraw:suspend ()->Unit={}):this(context.applicationContext.employeeNumberStore,redraw)
    private val key=stringPreferencesKey("employee_number")
    val value=store.data.map {it[key].orEmpty()}
    suspend fun set(value:String)=withContext(NonCancellable) {
        val trimmed=value.trim()
        var changed=false
        store.guardedEdit {prefs->
            changed=prefs[key].orEmpty()!=trimmed
            if(trimmed.isEmpty()) prefs.remove(key) else prefs[key]=trimmed
        }
        if(changed) redraw()
    }
}
