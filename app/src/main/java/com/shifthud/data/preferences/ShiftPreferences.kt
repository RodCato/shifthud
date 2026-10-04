package com.shifthud.data.preferences
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val Context.shiftPreferences by preferencesDataStore(name = "shift_preferences")
class ShiftPreferences(context: Context, private val onChanged: suspend () -> Unit = {}) {
    private val store = context.shiftPreferences
    private val lunchThreshold = intPreferencesKey("lunch_threshold_minutes")
    val lunchThresholdMinutes: Flow<Int> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { it[lunchThreshold] ?: 360 }
    suspend fun setLunchThreshold(minutes: Int) {
        require(minutes in 1..1440) { "Enter 1–1440 minutes." }
        store.edit { it[lunchThreshold] = minutes }
        withContext(NonCancellable) { onChanged() }
    }
}
