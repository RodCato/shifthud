package com.shifthud.frozen

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.shifthud.backup.guardedEdit
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID

private val Context.frozenTaskStore by preferencesDataStore(name="frozen_tasks")

/** Separate, atomic preferences; malformed data is surfaced, never reset to a template. */
class FrozenTaskStore(private val store: DataStore<Preferences>, private val clock: Clock = Clock.systemUTC()) {
    constructor(context: Context): this(context.applicationContext.frozenTaskStore)
    private val key=stringPreferencesKey("tasks_v1")
    val tasks=store.data.map { decode(it[key]) }
    suspend fun initialize() { store.guardedEdit { if(it[key]==null) it[key]=encode(mondayFrozenTemplate()) else decode(it[key]) } }
    suspend fun setState(id: String, state: FrozenState) = change(id) { task ->
        if(task.state==state) task else task.copy(state=state,history=task.history+FrozenEvent(state,clock.instant()))
    }
    suspend fun saveDetails(id: String, title: String, description: String, notes: String) {
        validate(title,description,notes)
        change(id) { it.copy(title=title.trim(),description=description.trim(),notes=notes.trim()) }
    }
    suspend fun add(title: String, description: String, notes: String) {
        validate(title,description,notes)
        store.guardedEdit { values ->
            val existing=decode(values[key]); check(values[key]!=null) { "Checklist is not loaded." }
            values[key]=encode(existing+FrozenTask(UUID.randomUUID().toString(),FROZEN_MONDAY,title.trim(),description.trim(),notes=notes.trim()))
        }
    }
    private suspend fun change(id:String, update:(FrozenTask)->FrozenTask) { store.guardedEdit { values ->
        val tasks=decode(values[key]); check(tasks.any { it.id==id }) { "Task no longer exists. Reopen Frozen Tasks." }
        values[key]=encode(tasks.map { if(it.id==id) update(it) else it })
    } }
    private fun validate(title:String,description:String,notes:String) {
        require(title.isNotBlank() && title.length<=160) { "Enter a task title (up to 160 characters)." }
        require(description.length<=3000 && notes.length<=1000) { "Reference must be at most 3000 characters; notes at most 1000." }
    }
    internal fun encode(tasks:List<FrozenTask>):String=JSONObject().put("version",1).put("tasks",JSONArray().apply {
        tasks.forEach { t -> put(JSONObject().put("id",t.id).put("date",t.date.toString()).put("title",t.title).put("description",t.description)
            .put("state",t.state.name).put("notes",t.notes).put("history",JSONArray().apply {
                t.history.forEach { put(JSONObject().put("state",it.state.name).put("at",it.at.toString())) }
            })) }
    }).toString()
    internal fun decode(raw:String?):List<FrozenTask> {
        if(raw==null)return emptyList()
        val root=JSONObject(raw); check(root.getInt("version")==1) { "Unsupported Frozen Tasks data version." }
        val rows=root.getJSONArray("tasks")
        val result=(0 until rows.length()).map { i ->
            val t=rows.getJSONObject(i); val history=t.getJSONArray("history")
            FrozenTask(t.getString("id"),LocalDate.parse(t.getString("date")),t.getString("title"),t.getString("description"),
                FrozenState.valueOf(t.getString("state")),t.getString("notes"),(0 until history.length()).map { j ->
                    val event=history.getJSONObject(j);FrozenEvent(FrozenState.valueOf(event.getString("state")),Instant.parse(event.getString("at")))
                }).also { check(it.id.isNotBlank());validate(it.title,it.description,it.notes) }
        }
        check(result.map { it.id }.distinct().size==result.size) { "Duplicate Frozen task IDs." }
        return result
    }
}
