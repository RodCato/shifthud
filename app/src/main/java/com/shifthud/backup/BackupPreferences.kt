package com.shifthud.backup

import android.content.Context
import androidx.datastore.preferences.core.*
import com.shifthud.data.preferences.*
import kotlinx.coroutines.flow.first
import org.json.*
import java.time.LocalDate
import com.shifthud.domain.pay.PayRate

internal val SHIFT_KEYS=mapOf(
    "show_decimal_hours" to "b", "weekly_lunch_plans" to "s", "clockout_planner" to "s", "lunch_threshold_minutes" to "i","auto_lunch_enabled" to "b","auto_lunch_minutes" to "i",
    "lunch_warning_offsets" to "set","lunch_snooze_minutes" to "i",
    "weekly_target_enabled" to "b","weekly_target_minutes" to "i","weekly_target_warnings" to "set",
    "shift_end_enabled" to "b","shift_end_lead" to "i","shift_end_snooze" to "i")
internal val RECEIPT_KEYS=setOf("planner_delivery","lunch_warning_delivery","lunch_attention","weekly_target_delivery","shift_end_delivery")
internal val UPCOMING_KEYS=mapOf("enabled" to "b","minute" to "i","days_off" to "b")
internal val FROZEN_KEYS=mapOf("tasks_v1" to "s","selected_workflow" to "s","truck_workflows_v1" to "s", "daily_workflow_dates" to "set","friday_template_dates_v1" to "set","daily_template_dates_v1" to "set","carryovers_archived_before_2026_11_01" to "b","daily_float_counts_v1" to "s","float_requests_v1" to "s")
internal val EXTRA_KEYS=mapOf("frozen" to FROZEN_KEYS,"countRoute" to mapOf("count_route_v1" to "s"),"employee" to mapOf("employee_number" to "s"),"lunchAlerts" to mapOf("minimum" to "b","planned" to "b","snooze" to "i","repeat" to "b"),"paycheckReminder" to mapOf("enabled" to "b","day" to "i","minute" to "i"))
internal val PREFERENCE_GROUPS=setOf("shift","rates","upcoming")+EXTRA_KEYS.keys
internal fun JSONObject.namesSet()=keys().asSequence().toSet()

class BackupPreferences(context:Context,private val shift:ShiftPreferences,private val rates:PayRatePreferences) {
    private val frozen=com.shifthud.frozen.FrozenTaskStore(context)
    private val stores=mapOf("frozen" to frozen.store,"countRoute" to com.shifthud.frozen.count.CountRouteStore(context).preferences,"employee" to EmployeeNumberPreferences(context).store)
    private val shared=mapOf("lunchAlerts" to context.getSharedPreferences("on_lunch_alerts",Context.MODE_PRIVATE),"paycheckReminder" to context.getSharedPreferences("paycheck_import_reminder",Context.MODE_PRIVATE))
    private val upcoming=context.getSharedPreferences("upcoming_reminders",Context.MODE_PRIVATE)
    suspend fun snapshot(runtime:Boolean=false):JSONObject {
        val s=shift.store.data.first().asMap().mapKeys{it.key.name}
        require((s.keys-SHIFT_KEYS.keys-RECEIPT_KEYS).isEmpty()){"Unrecognized preferences; update backup coverage before exporting."}
        val r=rates.store.data.first().asMap().mapKeys{it.key.name}
        require((r.keys-setOf("history_v1")).isEmpty())
        val u=upcoming.all
        require((u.keys-UPCOMING_KEYS.keys-setOf("date","state","posted")).isEmpty())
        return JSONObject().put("shift",encode(if(runtime)s else s.filterKeys{it in SHIFT_KEYS}))
            .put("rates",encode(r)).put("upcoming",encode(if(runtime)u else u.filterKeys{it in UPCOMING_KEYS})).also { result ->
                stores.forEach { (name,store) -> result.put(name,encode(store.data.first().asMap().mapKeys{it.key.name})) }
                shared.forEach { (name,prefs) ->
                    val allowed=EXTRA_KEYS.getValue(name).keys
                    val receipt=if(name=="lunchAlerts")"ledger" else "delivered"
                    require((prefs.all.keys-allowed-setOf(receipt)).isEmpty()){ "Unrecognized $name preferences." }
                    result.put(name,encode(if(runtime)prefs.all else prefs.all.filterKeys {it in allowed}))
                }
                validate(result,runtime);validateStores(result)
            }
    }
    // commit() result is required for crash-safe restore; KTX edit discards it.
    @android.annotation.SuppressLint("UseKtx")
    suspend fun replace(data:JSONObject,afterGroup:(String)->Unit={}) {
        replaceStore(shift.store,data.getJSONObject("shift"));afterGroup("shift")
        replaceStore(rates.store,data.getJSONObject("rates"));afterGroup("rates")
        val editor=upcoming.edit().clear()
        decode(data.getJSONObject("upcoming")).forEach{(k,v)->when(v) {
            is Boolean->editor.putBoolean(k,v);is Int->editor.putInt(k,v);is Long->editor.putLong(k,v);is String->editor.putString(k,v)
            else->error("Unsupported reminder preference.")
        }}
        check(editor.commit()){"Could not write reminder settings."}
        afterGroup("upcoming")
        stores.forEach { (name,store) -> replaceStore(store,data.getJSONObject(name));afterGroup(name) }
        shared.forEach { (name,prefs) ->
            val edit=prefs.edit().clear()
            decode(data.getJSONObject(name)).forEach { (key,value) -> when(value) {
                is Boolean -> edit.putBoolean(key,value);is Int -> edit.putInt(key,value);is String -> edit.putString(key,value)
                else -> error("Unsupported $name preference.")
            } }
            check(edit.commit()){ "Could not write $name settings." };afterGroup(name)
        }
    }
    internal fun validateStores(data:JSONObject) {
        val f=decode(data.getJSONObject("frozen"))
        frozen.decode(f["tasks_v1"] as? String)
        frozen.decodeTrucks(f["truck_workflows_v1"] as? String)
        com.shifthud.frozen.decodeFloatCounts(f["daily_float_counts_v1"] as? String)
        (f["float_requests_v1"] as? String)?.let {raw -> val a=JSONArray(raw);require(a.length()<=1024);val ids=(0 until a.length()).map {a.getString(it)};require(ids.distinct().size==ids.size && ids.all{it.isNotBlank() && it.length<=128}) }
        listOf("daily_workflow_dates","friday_template_dates_v1","daily_template_dates_v1").forEach { key -> (f[key] as? Set<*>)?.forEach {LocalDate.parse(it as String)} }
        com.shifthud.frozen.count.decodeCountRoute(decode(data.getJSONObject("countRoute"))["count_route_v1"] as? String)
    }
    companion object {
        fun encode(values:Map<String,*>):JSONObject=JSONObject().apply {
            values.toSortedMap().forEach{(k,v)->put(k,JSONObject().put("type",when(v){is Boolean->"b";is Int->"i";is Long->"l";is String->"s";is Set<*>->"set";else->error("Unsupported preference type.")})
                .put("value",if(v is Set<*>)JSONArray(v.map{it as String}.sorted()) else v))}
        }
        fun decode(j:JSONObject):Map<String,Any> = j.namesSet().associateWith{k->
            val entry=j.getJSONObject(k);val v=entry.get("value")
            when(entry.getString("type")){
                "b"->v.also{require(it is Boolean)}
                "i"->{require(v is Int || v is Long);v.toString().toInt()}
                "l"->{require(v is Int || v is Long);v.toString().toLong()}
                "s"->v.also{require(it is String)}
                "set"->(v as JSONArray).let{a->(0 until a.length()).map{a.get(it).also{v2->require(v2 is String)} as String}.toSet()}
                else->error("Unsupported preference type.")
            }
        }
        fun validate(data:JSONObject,runtime:Boolean=false) {
            require(data.namesSet()==PREFERENCE_GROUPS)
            fun fields(name:String,allowed:Map<String,String>):Map<String,Any> {
                val j=data.getJSONObject(name)
                require((j.namesSet()-allowed.keys).isEmpty()){"Unsupported preference in $name."}
                j.namesSet().forEach{require(j.getJSONObject(it).getString("type")==allowed[it])}
                return decode(j)
            }
            EXTRA_KEYS.forEach { (name,keys) ->
                val extra=if(runtime && name=="lunchAlerts")mapOf("ledger" to "s") else if(runtime && name=="paycheckReminder")mapOf("delivered" to "s") else emptyMap()
                val values=fields(name,keys+extra)
                if(name=="lunchAlerts")values["snooze"]?.let{require(it as Int in 1..120)}
                if(name=="paycheckReminder") {values["day"]?.let{require(it as Int in 1..7)};values["minute"]?.let{require(it as Int in 0..1439)}}
            }
            val s=fields("shift",SHIFT_KEYS+if(runtime)RECEIPT_KEYS.associateWith{"s"} else emptyMap())
            (s["weekly_lunch_plans"] as? String)?.let { ShiftPreferences.decodeWeeklyLunches(it) }
            (s["clockout_planner"] as? String)?.let { ShiftPreferences.decodePlanner(it) }
            fun range(key:String,range:IntRange){s[key]?.let{require(it as Int in range){"Invalid $key."}}}
            range("lunch_threshold_minutes",1..1440);range("auto_lunch_minutes",1..1440)
            range("weekly_target_minutes",1..10080);range("lunch_snooze_minutes",1..1440)
            range("shift_end_lead",0..1440);range("shift_end_snooze",1..1440)
            listOf("lunch_warning_offsets","weekly_target_warnings").forEach{k->(s[k] as? Set<*>)?.forEach{require((it as String).toInt() in 1..1440)}}
            fun choices(key:String,allowed:Set<Int>){s[key]?.let{require(it as Int in allowed){"Unsupported $key setting."}}}
            choices("auto_lunch_minutes",setOf(2,30,45,60,90))
            choices("lunch_snooze_minutes",com.shifthud.notification.SNOOZE_CHOICES.toSet())
            choices("shift_end_lead",com.shifthud.notification.SHIFT_END_LEADS.toSet()+2)
            choices("shift_end_snooze",com.shifthud.notification.SHIFT_END_SNOOZES.toSet())
            (s["weekly_target_warnings"] as? Set<*>)?.forEach{require((it as String).toInt() in com.shifthud.domain.weekly.WEEKLY_WARNING_MINUTES)}
            val r=fields("rates",mapOf("history_v1" to "s"))
            (r["history_v1"] as? String)?.let{raw->
                val a=JSONArray(raw)
                val rows=(0 until a.length()).map{a.getJSONObject(it).let{v->PayRate(LocalDate.parse(v.getString("from")),v.get("cents").toString().toLong())}}
                require(rows.isNotEmpty() && rows.first()==com.shifthud.domain.pay.DEFAULT_PAY_RATES.single())
                require(rows.map{it.effectiveFrom}.distinct().size==rows.size)
            }
            val u=fields("upcoming",UPCOMING_KEYS+if(runtime)mapOf("date" to "s","state" to "s","posted" to "l") else emptyMap())
            u["minute"]?.let{require(it as Int in 0..1439)}
        }
        private suspend fun replaceStore(store:androidx.datastore.core.DataStore<Preferences>,j:JSONObject) {
            store.guardedEdit { p->p.clear();decode(j).forEach{(k,v)->when(v){
                is Boolean->p[booleanPreferencesKey(k)]=v;is Int->p[intPreferencesKey(k)]=v
                is Long->p[longPreferencesKey(k)]=v;is String->p[stringPreferencesKey(k)]=v
                is Set<*>->{@Suppress("UNCHECKED_CAST") val set=v as Set<String>;p[stringSetPreferencesKey(k)]=set}
            }}}
        }
    }
}
