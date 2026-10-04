package com.shifthud.data.preferences
import android.content.Context
import com.shifthud.notification.*
import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.ShiftEngine
import java.time.Instant
import org.json.JSONObject
import org.json.JSONArray
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
    private val warningOffsets = stringSetPreferencesKey("lunch_warning_offsets")
    private val warningHistory = stringPreferencesKey("lunch_warning_delivery")
    private fun settings(p: Preferences) = WarningSettings(p[lunchThreshold] ?: 360,
        p[warningOffsets]?.mapNotNull { it.toIntOrNull() }?.filter { it in 1..1440 }?.toSet() ?: DEFAULT_WARNING_OFFSETS)
    val warningSettings: Flow<WarningSettings> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map(::settings)
    suspend fun setWarningEnabled(offset: Int, enabled: Boolean) {
        require(offset in 1..1440)
        store.edit { p ->
            val offsets = settings(p).offsets
            p[warningOffsets] = (if (enabled) offsets + offset else offsets - offset).map { it.toString() }.toSet()
        }
        withContext(NonCancellable) { onChanged() }
    }
    // Serialize evaluation/post/ack with settings edits. A failed or blocked post never consumes
    // the current candidate. Posting receipts in Android reconcile a crash before this edit commits.
    suspend fun deliverWarning(session: WorkSession?, now: Instant, engine: ShiftEngine, allowed: Boolean,
                               onEvaluated: (WarningDecision) -> Unit = {},
                               post: (WarningDecision) -> Boolean): WarningDecision {
        var decision = WarningDecision(null)
        store.edit { p ->
            val previous = p[warningHistory]?.let { encoded -> runCatching {
                val json = JSONObject(encoded)
                fun values(key: String): Set<Int> = json.getJSONArray(key).let { array -> (0 until array.length()).map { array.getInt(it) }.toSet() }
                WarningLedger(json.getLong("session"), WarningSettings(json.getInt("threshold"), values("offsets")), values("consumed"))
            }.getOrNull() }
            decision = evaluateLunchWarning(session, settings(p), previous, now, engine, allowed)
            onEvaluated(decision)
            if (decision.offset != null && post(decision)) decision = decision.afterSuccessfulPost()
            if (decision.ledger == null) p.remove(warningHistory)
            else if (decision.ledger != previous) {
                val ledger = decision.ledger!!
                p[warningHistory] = JSONObject().put("session", ledger.sessionId).put("threshold", ledger.settings.threshold)
                    .put("offsets", JSONArray(ledger.settings.offsets.sorted())).put("consumed", JSONArray(ledger.consumed.sorted())).toString()
            }
        }
        return decision
    }
}
