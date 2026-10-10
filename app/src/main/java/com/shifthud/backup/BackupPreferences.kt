package com.shifthud.backup

import android.content.Context
import androidx.datastore.preferences.core.*
import com.shifthud.data.preferences.*
import kotlinx.coroutines.flow.first
import org.json.*
import java.time.LocalDate
import com.shifthud.domain.pay.PayRate

internal val SHIFT_KEYS=mapOf(
    "lunch_threshold_minutes" to "i","auto_lunch_enabled" to "b","auto_lunch_minutes" to "i",
    "lunch_warning_offsets" to "set","lunch_snooze_minutes" to "i",
    "weekly_target_enabled" to "b","weekly_target_minutes" to "i","weekly_target_warnings" to "set",
    "shift_end_enabled" to "b","shift_end_lead" to "i","shift_end_snooze" to "i")
internal val RECEIPT_KEYS=setOf("lunch_warning_delivery","lunch_attention","weekly_target_delivery","shift_end_delivery")
internal val UPCOMING_KEYS=mapOf("enabled" to "b","minute" to "i","days_off" to "b")
internal fun JSONObject.namesSet()=keys().asSequence().toSet()

class BackupPreferences(context:Context,private val shift:ShiftPreferences,private val rates:PayRatePreferences) {
    private val upcoming=context.getSharedPreferences("upcoming_reminders",Context.MODE_PRIVATE)
    suspend fun snapshot(runtime:Boolean=false):JSONObject {
        val s=shift.store.data.first().asMap().mapKeys{it.key.name}
        require((s.keys-SHIFT_KEYS.keys-RECEIPT_KEYS).isEmpty()){"Unrecognized preferences; update backup coverage before exporting."}
        val r=rates.store.data.first().asMap().mapKeys{it.key.name}
        require((r.keys-setOf("history_v1")).isEmpty())
        val u=upcoming.all
        require((u.keys-UPCOMING_KEYS.keys-setOf("date","state","posted")).isEmpty())
        return JSONObject().put("shift",encode(if(runtime)s else s.filterKeys{it in SHIFT_KEYS}))
            .put("rates",encode(r)).put("upcoming",encode(if(runtime)u else u.filterKeys{it in UPCOMING_KEYS}))
    }
    // commit() result is required for crash-safe restore; KTX edit discards it.
    @android.annotation.SuppressLint("UseKtx")
    suspend fun replace(data:JSONObject) {
        replaceStore(shift.store,data.getJSONObject("shift"))
        replaceStore(rates.store,data.getJSONObject("rates"))
        val editor=upcoming.edit().clear()
        decode(data.getJSONObject("upcoming")).forEach{(k,v)->when(v) {
            is Boolean->editor.putBoolean(k,v);is Int->editor.putInt(k,v);is Long->editor.putLong(k,v);is String->editor.putString(k,v)
            else->error("Unsupported reminder preference.")
        }}
        check(editor.commit()){"Could not write reminder settings."}
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
            require(data.namesSet()==setOf("shift","rates","upcoming"))
            fun fields(name:String,allowed:Map<String,String>):Map<String,Any> {
                val j=data.getJSONObject(name)
                require((j.namesSet()-allowed.keys).isEmpty()){"Unsupported preference in $name."}
                j.namesSet().forEach{require(j.getJSONObject(it).getString("type")==allowed[it])}
                return decode(j)
            }
            val s=fields("shift",SHIFT_KEYS+if(runtime)RECEIPT_KEYS.associateWith{"s"} else emptyMap())
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
