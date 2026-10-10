package com.shifthud.data.preferences
import android.content.Context
import com.shifthud.notification.*
import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.usecase.*
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
    val debugSettings = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val store = context.shiftPreferences
    private val lunchThreshold = intPreferencesKey("lunch_threshold_minutes")
    val lunchThresholdMinutes: Flow<Int> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { it[lunchThreshold] ?: 360 }
    suspend fun setLunchThreshold(minutes: Int) {
        require(minutes in 1..1440) { "Enter 1–1440 minutes." }
        store.edit { it[lunchThreshold] = minutes }
        withContext(NonCancellable) { onChanged() }
    }
    private val autoEnabled = booleanPreferencesKey("auto_lunch_enabled")
    private val autoMinutes = intPreferencesKey("auto_lunch_minutes")
    val autoLunchSettings: Flow<AutoLunchSettings> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        AutoLunchSettings(it[autoEnabled] ?: true, (it[autoMinutes] ?: 60).takeIf { n -> n in AUTO_LUNCH_CHOICES || (debugSettings && n == 2) } ?: 60)
    }
    suspend fun setAutoLunch(enabled: Boolean, minutes: Int) {
        require(minutes in AUTO_LUNCH_CHOICES || (debugSettings && minutes == 2))
        store.edit { it[autoEnabled] = enabled; it[autoMinutes] = minutes }
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
    private val snoozeDuration = intPreferencesKey("lunch_snooze_minutes")
    private val attentionHistory = stringPreferencesKey("lunch_attention")
    val snoozeMinutes: Flow<Int> = store.data.map { it[snoozeDuration]?.takeIf { n -> n in SNOOZE_CHOICES } ?: 10 }
    suspend fun setSnoozeMinutes(minutes: Int) {
        require(minutes in SNOOZE_CHOICES)
        store.edit { it[snoozeDuration] = minutes }
        withContext(NonCancellable) { onChanged() }
    }
    suspend fun forgetSession(sessionId: Long) {
        store.edit { p ->
            listOf(warningHistory, attentionHistory, endHistory).forEach { key ->
                val owner = p[key]?.let { runCatching { JSONObject(it).getLong("session") }.getOrNull() }
                if (owner == sessionId) p.remove(key)
            }
        }
    }
    private val weeklyEnabled = booleanPreferencesKey("weekly_target_enabled")
    private val weeklyMinutes = intPreferencesKey("weekly_target_minutes")
    private val weeklyOffsets = stringSetPreferencesKey("weekly_target_warnings")
    private val weeklyHistory = stringPreferencesKey("weekly_target_delivery")
    private fun weeklySettings(p: Preferences) = com.shifthud.domain.weekly.WeeklyTargetSettings(p[weeklyEnabled] ?: true,
        p[weeklyMinutes]?.takeIf { it in 1..10080 } ?: 2400,
        p[weeklyOffsets]?.mapNotNull { it.toIntOrNull() }?.filter { it in com.shifthud.domain.weekly.WEEKLY_WARNING_MINUTES }?.toSet()
            ?: com.shifthud.domain.weekly.WEEKLY_WARNING_MINUTES)
    val weeklyTargetSettings = store.data.map(::weeklySettings)
    suspend fun setWeeklyTarget(settings: com.shifthud.domain.weekly.WeeklyTargetSettings) {
        store.edit { p ->
            p[weeklyEnabled] = settings.enabled; p[weeklyMinutes] = settings.targetMinutes
            p[weeklyOffsets] = settings.warnings.map { it.toString() }.toSet()
        }
        withContext(NonCancellable) { onChanged() }
    }
    suspend fun deliverWeeklyWarning(sessions: List<WorkSession>, schedule: List<com.shifthud.domain.model.ScheduledShift>,
                                    now: Instant, zone: java.time.ZoneId,
                                    post: (WeeklyWarningDecision, com.shifthud.domain.weekly.WeeklyTargetProgress) -> Boolean): WeeklyWarningDecision {
        lateinit var decision: WeeklyWarningDecision
        store.edit { p ->
            val settings = weeklySettings(p)
            val week = com.shifthud.domain.pay.workWeekFor(now.atZone(zone).toLocalDate()).start.toString()
            // Retain receipts for each target tried this week, including switches back to an old target.
            val history = p[weeklyHistory]?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optString("week") == week } ?: JSONObject().put("week", week)
            val targets = history.optJSONObject("targets") ?: JSONObject()
            val targetKey = settings.targetMinutes.toString()
            val previous = targets.optJSONObject(targetKey)?.let { j -> runCatching {
                fun numbers(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
                WeeklyWarningLedger(java.time.LocalDate.parse(week),
                    com.shifthud.domain.weekly.WeeklyTargetSettings(j.getBoolean("enabled"), settings.targetMinutes, numbers("warnings")),
                    numbers("consumed"), j.getString("corrections"))
            }.getOrNull() }
            val progress = com.shifthud.domain.weekly.weeklyTarget(sessions, schedule, settings, p[autoMinutes] ?: 60, now, zone)
            decision = evaluateWeeklyWarning(progress, settings, previous, now, history.optString("activeTarget") != targetKey)
            if (decision.boundary != null && post(decision, progress)) decision = decision.acknowledged()
            val ledger = decision.ledger
            if (ledger != previous || history.optString("activeTarget") != targetKey) {
                targets.put(targetKey, JSONObject().put("enabled", ledger.settings.enabled)
                    .put("warnings", JSONArray(ledger.settings.warnings.sorted())).put("consumed", JSONArray(ledger.consumed.sorted()))
                    .put("corrections", ledger.correctionKey))
                p[weeklyHistory] = history.put("targets", targets).put("activeTarget", targetKey).toString()
            }
        }
        return decision
    }
    private val endEnabled = booleanPreferencesKey("shift_end_enabled")
    private val endLead = intPreferencesKey("shift_end_lead")
    private val endSnooze = intPreferencesKey("shift_end_snooze")
    private val endHistory = stringPreferencesKey("shift_end_delivery")
    private fun endSettings(p: Preferences) = ShiftEndSettings(p[endEnabled] ?: true,
        p[endLead]?.takeIf { it in SHIFT_END_LEADS || (debugSettings && it == 2) } ?: 15,
        p[endSnooze]?.takeIf { it in SHIFT_END_SNOOZES } ?: 10)
    val shiftEndSettings = store.data.map(::endSettings)
    suspend fun setShiftEnd(settings: ShiftEndSettings) {
        require(settings.leadMinutes in SHIFT_END_LEADS || (debugSettings && settings.leadMinutes == 2))
        require(settings.snoozeMinutes in SHIFT_END_SNOOZES)
        store.edit { it[endEnabled] = settings.enabled; it[endLead] = settings.leadMinutes; it[endSnooze] = settings.snoozeMinutes }
        withContext(NonCancellable) { onChanged() }
    }
    private fun endState(p: Preferences): ShiftEndDelivery? = p[endHistory]?.let { encoded -> runCatching {
        val j = JSONObject(encoded)
        ShiftEndDelivery(j.getLong("session"), Instant.ofEpochMilli(j.getLong("end")), j.getInt("lead"),
            Instant.ofEpochMilli(j.getLong("target")), j.getLong("generation"), j.getBoolean("posted"), j.getBoolean("skipped"))
    }.getOrNull() }
    private fun saveEnd(p: MutablePreferences, state: ShiftEndDelivery?) {
        if (state == null) p.remove(endHistory)
        else p[endHistory] = JSONObject().put("session", state.sessionId).put("end", state.end.toEpochMilli())
            .put("lead", state.lead).put("target", state.target.toEpochMilli()).put("generation", state.generation)
            .put("posted", state.posted).put("skipped", state.skipped).toString()
    }
    suspend fun deliverShiftEnd(snapshot: com.shifthud.data.repository.ShiftSnapshot, now: Instant, zone: java.time.ZoneId,
                                post: (ShiftEndDelivery, Int) -> Boolean): ShiftEndDecision {
        var result = ShiftEndDecision(null)
        store.edit { p ->
            val previous = endState(p)
            val settings = endSettings(p)
            result = evaluateShiftEnd(snapshot, settings, previous, now, zone)
            if (result.due && post(result.state!!, settings.snoozeMinutes))
                result = result.copy(state = result.state!!.copy(posted = true), cancel = false)
            if (result.state != previous) saveEnd(p, result.state)
        }
        return result
    }
    suspend fun snoozeShiftEnd(receipt: String, current: suspend () -> com.shifthud.data.repository.ShiftSnapshot,
                               now: Instant, zone: java.time.ZoneId): Boolean {
        var accepted = false
        store.edit { p ->
            val next = com.shifthud.notification.snoozeShiftEnd(current(), endSettings(p), endState(p), receipt, now, zone)
            if (next != null) { saveEnd(p, next); accepted = true }
        }
        return accepted
    }
    suspend fun nextShiftEndTarget(): Instant? = endState(store.data.first())?.takeIf { !it.posted && !it.skipped }?.target
    private fun attention(p: Preferences): LunchAttention? = p[attentionHistory]?.let { encoded ->
        runCatching {
            val j = JSONObject(encoded)
            LunchAttention(j.getLong("session"), j.getLong("generation"),
                if (j.has("target")) j.getLong("target") else null, j.getBoolean("posted"), j.optLong("revision", 0), j.optBoolean("skipped", false))
        }.getOrNull()
    }
    private fun saveAttention(p: MutablePreferences, state: LunchAttention?) {
        if (state == null) p.remove(attentionHistory)
        else p[attentionHistory] = JSONObject().put("session", state.sessionId).put("generation", state.generation)
            .put("target", state.targetActiveMillis).put("posted", state.posted).put("revision", state.revision).put("skipped", state.skipped).toString()
    }
    suspend fun deliverAttention(session: WorkSession?, now: Instant, engine: ShiftEngine,
                                 allowed: Boolean, post: (LunchAttention, Int) -> Boolean): AttentionDecision {
        var result = AttentionDecision(null)
        store.edit { p ->
            val previous = attention(p)
            result = evaluateLunchAttention(session, previous, settings(p).threshold, now, engine)
            if (result.due && allowed && post(result.state!!, p[snoozeDuration] ?: 10)) result = result.acknowledged()
            if (result.state != previous) saveAttention(p, result.state)
        }
        return result
    }
    // Read authoritative Room state inside the serialized edit, never trust notification timestamps.
    suspend fun snooze(sessionId: Long, receipt: String, current: suspend () -> WorkSession?,
                       now: Instant, engine: ShiftEngine): Boolean {
        var accepted = false
        store.edit { p ->
            val session = current()?.takeIf { it.id == sessionId }
            val next = snoozeLunchAttention(session, attention(p), receipt, settings(p).threshold,
                p[snoozeDuration] ?: 10, now, engine)
            if (next != null) { saveAttention(p, next); accepted = true }
        }
        return accepted
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
                WarningLedger(json.getLong("session"), WarningSettings(json.getInt("threshold"), values("offsets")), values("consumed"), json.optLong("revision", 0))
            }.getOrNull() }
            decision = evaluateLunchWarning(session, settings(p), previous, now, engine, allowed)
            onEvaluated(decision)
            if (decision.offset != null && post(decision)) decision = decision.afterSuccessfulPost()
            if (decision.ledger == null) p.remove(warningHistory)
            else if (decision.ledger != previous) {
                val ledger = decision.ledger!!
                p[warningHistory] = JSONObject().put("session", ledger.sessionId).put("threshold", ledger.settings.threshold)
                    .put("offsets", JSONArray(ledger.settings.offsets.sorted())).put("consumed", JSONArray(ledger.consumed.sorted())).put("revision", ledger.revision).toString()
            }
        }
        return decision
    }
}
