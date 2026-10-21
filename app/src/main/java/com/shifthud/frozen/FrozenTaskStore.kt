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
    private val selectedKey=stringPreferencesKey("selected_workflow")
    private val trucksKey=stringPreferencesKey("truck_workflows_v1")
    val truckWorkflows=store.data.map { decodeTrucks(it[trucksKey]) }
    val selectedWorkflow=store.data.map { it[selectedKey].orEmpty() }
    val tasks=store.data.map { decode(it[key]) }
    suspend fun initialize() { store.guardedEdit { if(it[key]==null) it[key]=encode(mondayFrozenTemplate()) else decode(it[key]);decodeTrucks(it[trucksKey]) } }
    suspend fun setState(id: String, state: FrozenState) = change(id) { task ->
        if(task.state==state) task else task.copy(state=state,history=task.history+FrozenEvent(state,clock.instant()))
    }
    suspend fun saveDetails(id: String, title: String, description: String, notes: String) {
        validate(title,description,notes)
        change(id) { it.copy(title=title.trim(),description=description.trim(),notes=notes.trim(),sourceNames=if(it.plan.isNotBlank()) (it.sourceNames+normalizeEndcap(it.title)).distinct() else it.sourceNames) }
    }
    suspend fun add(title: String, description: String, notes: String) {
        validate(title,description,notes)
        store.guardedEdit { values ->
            val existing=decode(values[key]); check(values[key]!=null) { "Checklist is not loaded." }
            values[key]=encode(existing+FrozenTask(UUID.randomUUID().toString(),values[selectedKey]?.takeIf { it.isNotBlank() }?.let(::frozenWorkflowDate) ?: FROZEN_MONDAY,title.trim(),description.trim(),notes=notes.trim(),workflows=listOfNotNull(values[selectedKey]?.takeIf { it.isNotBlank() }),section=if(isWednesdayWorkflow(values[selectedKey].orEmpty())) "BEFORE TRUCK" else "MORNING TRANSITION"))
        }
    }
    suspend fun selectWorkflow(workflow: String) { store.guardedEdit { it[selectedKey]=workflow } }
    suspend fun startTuesday(date: LocalDate) { store.guardedEdit { values ->
        val existing=decode(values[key]); check(values[key]!=null)
        val workflow=date.toString()
        val linked=existing.map { if(it.state==FrozenState.CARRY_OVER && workflow !in it.workflows)
            it.copy(workflows=it.workflows+workflow) else it }
        val template=tuesdayFrozenTemplate(date)
        values[key]=encode(linked+template.filter { candidate -> existing.none { it.id==candidate.id } })
        values[selectedKey]=workflow
    } }
    suspend fun startWednesday(date:LocalDate) { store.guardedEdit { values ->
        val existing=decode(values[key]);check(values[key]!=null)
        val workflow=wednesdayWorkflowId(date)
        val records=decodeTrucks(values[trucksKey])
        val carryovers=existing.filter { it.state==FrozenState.CARRY_OVER && it.date<=date }
        val linked=existing.map { if(it in carryovers && workflow !in it.workflows) it.copy(workflows=it.workflows+workflow) else it }
        val record=records.singleOrNull { it.date==date }
        if(record==null) {
            val added=wednesdayFrozenTemplate(date).filter { candidate ->
                existing.none { it.id==candidate.id } && carryovers.none { frozenTruckTaskType(it)==frozenTruckTaskType(candidate) }
            }
            values[key]=encode(linked+added)
            values[trucksKey]=encodeTrucks(records+FrozenTruckWorkflow(date,(carryovers.map { it.id }+added.map { it.id }).distinct()))
        } else {
            values[key]=encode(linked)
            values[trucksKey]=encodeTrucks(records.map { if(it.date==date) it.copy(taskIds=(it.taskIds+carryovers.map { task->task.id }).distinct()) else it })
        }
        values[selectedKey]=workflow
    } }
    suspend fun setTruckArrived(date:LocalDate,arrived:Boolean,zone:ZoneId=ZoneId.systemDefault()) { store.guardedEdit { values ->
        val records=decodeTrucks(values[trucksKey]);check(records.any { it.date==date }) { "Open a saved Wednesday workflow first." }
        values[trucksKey]=encodeTrucks(records.map { record ->
            if(record.date!=date || (record.arrival!=null)==arrived) record
            else record.copy(events=record.events+TruckArrivalEvent(arrived,clock.instant(),zone.id))
        })
    } }
    internal fun encodeTrucks(records:List<FrozenTruckWorkflow>):String=JSONObject().put("version",1).put("workflows",JSONArray().apply {
        records.forEach { record -> put(JSONObject().put("date",record.date.toString()).put("taskIds",JSONArray(record.taskIds))
            .put("events",JSONArray().apply { record.events.forEach { event ->
                put(JSONObject().put("arrived",event.arrived).put("at",event.at.toString()).put("zone",event.zone))
            } })) }
    }).toString()
    internal fun decodeTrucks(raw:String?):List<FrozenTruckWorkflow> {
        if(raw==null)return emptyList()
        val root=JSONObject(raw);check(root.getInt("version")==1) { "Unsupported Frozen truck data version." }
        val records=root.getJSONArray("workflows")
        return (0 until records.length()).map { index ->
            val record=records.getJSONObject(index);val ids=record.getJSONArray("taskIds");val events=record.getJSONArray("events")
            FrozenTruckWorkflow(LocalDate.parse(record.getString("date")),(0 until ids.length()).map { ids.getString(it) },
                (0 until events.length()).map { i->val event=events.getJSONObject(i)
                    TruckArrivalEvent(event.getBoolean("arrived"),Instant.parse(event.getString("at")),ZoneId.of(event.getString("zone")).id)
                })
        }.also { rows->check(rows.map { it.date }.distinct().size==rows.size) { "Duplicate Frozen truck workflows." } }
    }
    suspend fun importProducts(date: LocalDate, week: LocalDate, location: String, products: List<EndcapProduct>,reference:String=ENDCAP_LAYOUT_REFERENCE) {
        require(week.dayOfWeek==DayOfWeek.SATURDAY) { "Choose the Saturday starting the promotional week." }
        require(location.isNotBlank() && location.length<=160 && reference.length<=1500)
        val reviewed=distinctEndcapProducts(products)
        require(reviewed.isNotEmpty()) { "Add at least one product." }
        reviewed.forEach { validate(it.name,"","");require(it.sku.length<=60 && it.family.length<=160 && it.category in ENDCAP_CATEGORIES) }
        reviewed.filter { it.family.isNotBlank() }.groupBy { normalizeEndcap(it.family) }.values.forEach { rows ->
            require(rows.map { it.category }.distinct().size==1) { "Choose one layout category for each family." }
        }
        store.guardedEdit { values ->
            val existing=decode(values[key]);check(values[key]!=null)
            val plan="$week|${normalizeEndcap(location)}"
            val old=existing.filter { it.plan==plan }
            val additions=reviewed.filter { product -> old.none { endcapTaskContains(it,product) } }
            val newTasks=mutableListOf<FrozenTask>()
            var updated=existing
            additions.groupBy { if(it.family.isBlank()) "sku:${it.sku}:${normalizeEndcap(it.name)}" else "family:${normalizeEndcap(it.family)}" }.values.forEach { rows ->
                val first=rows.first();val family=first.family.trim()
                val prior=old.singleOrNull { family.isNotBlank() && normalizeEndcap(it.family)==normalizeEndcap(family) }
                if(prior!=null) {
                    // Confirmed additions only. Retain original title, category, notes, chosen varieties and progress.
                    updated=updated.map { if(it.id==prior.id) it.copy(eligible=it.eligible+rows) else it }
                } else {
                    newTasks+=FrozenTask(UUID.randomUUID().toString(),date,if(family.isBlank()) first.name.trim() else family,
                        (if(family.isBlank()) "Pick one case of this product from the ad wall." else "Pick one representative case; test shelf fit, then choose additional eligible varieties as needed. Not every variety must be placed.")+"\n$location · Week $week\n$reference",
                        workflows=listOf(date.toString()),section="ENDCAP — PICK FOR LAYOUT",sku=if(family.isBlank()) first.sku.trim() else "",plan=plan,
                        family=family,category=first.category,eligible=if(family.isBlank()) emptyList() else rows,
                        sourceNames=if(family.isBlank()) (first.sourceNames+normalizeEndcap(first.name)).distinct() else emptyList(),sourceSkus=if(family.isBlank()) first.sourceSkus else emptyList())
                }
            }
            values[key]=encode(updated.map { if(it.plan==plan && date.toString() !in it.workflows) it.copy(workflows=it.workflows+date.toString()) else it }+newTasks)
        }
    }
    suspend fun chooseEndcapVariety(id:String,product:EndcapProduct,chosen:Boolean)=change(id) { task ->
        require(task.family.isNotBlank() && task.eligible.any { sameEndcapProduct(it,product) })
        val identity=endcapProductIdentity(product)
        task.copy(chosen=if(chosen) (task.chosen+identity).distinct() else task.chosen-identity)
    }
    suspend fun editProduct(id:String,name:String,sku:String) {
        validate(name,"","");require(sku.length<=60)
        store.guardedEdit { values ->
            val tasks=decode(values[key]);val task=tasks.single { it.id==id };require(task.plan.isNotBlank())
            require(tasks.none { it.id!=id && it.plan==task.plan && sameEndcapProduct(EndcapProduct(name,sku),EndcapProduct(it.title,it.sku)) }) { "This product already exists in this display plan." }
            values[key]=encode(tasks.map { if(it.id==id) it.copy(title=name.trim(),sku=sku.trim(),sourceNames=(it.sourceNames+normalizeEndcap(it.title)).distinct(),sourceSkus=(it.sourceSkus+listOfNotNull(it.sku.takeIf { sku->sku.isNotBlank() }?.let(::normalizeEndcap))).distinct()) else it })
        }
    }
    suspend fun removeProduct(id:String) { store.guardedEdit { values ->
        val tasks=decode(values[key]);require(tasks.single { it.id==id }.plan.isNotBlank())
        values[key]=encode(tasks.filterNot { it.id==id })
    } }
    private suspend fun change(id:String, update:(FrozenTask)->FrozenTask) { store.guardedEdit { values ->
        val tasks=decode(values[key]); check(tasks.any { it.id==id }) { "Task no longer exists. Reopen Frozen Tasks." }
        val selected=values[selectedKey].orEmpty()
        val updated=tasks.map { task -> if(task.id==id) {
            val changed=update(task)
            if(task.state==FrozenState.CARRY_OVER && selected.isNotBlank() && selected !in changed.workflows)
                changed.copy(workflows=changed.workflows+selected) else changed
        } else task }
        val changed=updated.single { it.id==id }
        require(changed.plan.isBlank() || updated.none { it.id!=id && it.plan==changed.plan && sameEndcapProduct(EndcapProduct(it.title,it.sku),EndcapProduct(changed.title,changed.sku)) }) { "This product already exists in this display plan." }
        values[key]=encode(updated)
    } }
    private fun validate(title:String,description:String,notes:String) {
        require(title.isNotBlank() && title.length<=160) { "Enter a task title (up to 160 characters)." }
        require(description.length<=3000 && notes.length<=1000) { "Reference must be at most 3000 characters; notes at most 1000." }
    }
    internal fun encode(tasks:List<FrozenTask>):String=JSONObject().put("version",1).put("tasks",JSONArray().apply {
        tasks.forEach { t -> put(JSONObject().put("id",t.id).put("date",t.date.toString()).put("title",t.title).put("description",t.description)
            .put("family",t.family).put("category",t.category).put("chosen",JSONArray(t.chosen)).put("sourceNames",JSONArray(t.sourceNames)).put("sourceSkus",JSONArray(t.sourceSkus))
            .put("eligible",JSONArray().apply { t.eligible.forEach { put(JSONObject().put("name",it.name).put("sku",it.sku).put("family",it.family).put("category",it.category).put("sourceNames",JSONArray(it.sourceNames)).put("sourceSkus",JSONArray(it.sourceSkus))) } })
            .put("workflows",JSONArray(t.workflows)).put("section",t.section).put("sku",t.sku).put("plan",t.plan).put("state",t.state.name).put("notes",t.notes).put("history",JSONArray().apply {
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
                }, t.optJSONArray("workflows")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                t.optString("section"),t.optString("sku"),t.optString("plan"),t.optString("family"),t.optString("category","Unassigned"),
                t.optJSONArray("eligible")?.let { a->(0 until a.length()).map { j->val p=a.getJSONObject(j);EndcapProduct(p.getString("name"),p.optString("sku"),p.optString("family"),p.optString("category","Unassigned"),p.optJSONArray("sourceNames")?.let { n->(0 until n.length()).map { n.getString(it) } }.orEmpty(),p.optJSONArray("sourceSkus")?.let { n->(0 until n.length()).map { n.getString(it) } }.orEmpty()) } }.orEmpty(),
                t.optJSONArray("chosen")?.let { a->(0 until a.length()).map { a.getString(it) } }.orEmpty(),
                t.optJSONArray("sourceNames")?.let { a->(0 until a.length()).map { a.getString(it) } }.orEmpty(),
                t.optJSONArray("sourceSkus")?.let { a->(0 until a.length()).map { a.getString(it) } }.orEmpty()).also { check(it.id.isNotBlank());validate(it.title,it.description,it.notes) }
        }
        check(result.map { it.id }.distinct().size==result.size) { "Duplicate Frozen task IDs." }
        return result
    }
}
