package com.shifthud.ui.scheduleimport

import android.net.Uri
import androidx.lifecycle.*
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.scheduleimport.*
import com.shifthud.domain.model.ScheduledShift
import com.shifthud.ui.paystub.PaystubOcr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import org.json.*

/** Only recognized text and user-reviewed fields enter saved instance state; never an image/URI. */
class ScheduleImportViewModel(private val app:ShiftHudApplication,private val saved:SavedStateHandle):ViewModel() {
    val schedule=app.repository.schedule
    val planning=app.preferences.plannerSettings
    val geometry=saved.getStateFlow("scheduleGeometry","")
    val raw=saved.getStateFlow("scheduleRaw","")
    val weekText=saved.getStateFlow("scheduleWeek","")
    val encoded=saved.getStateFlow("scheduleRows","")
    val confirmed=saved.getStateFlow("scheduleWeekConfirmed",false)
    val message=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    var expected:List<ScheduledShift>?=null
        private set
    fun capturePreview(value:List<ScheduledShift>) { expected=value }
    fun read(uri:Uri) {
        if(busy.value)return
        busy.value=true;message.value=null
        viewModelScope.launch {
            try {
                val document=PaystubOcr(app).readDocument(uri);ensureActive()
                val encodedGeometry=document.encodeGeometry()
                val text=com.shifthud.ui.paystub.ocrRows(document.lines.map{it.bounds}).take(20000)
                val defaults=planning.first()
                val extraction=extractPassportSchedule(document,defaultLunch=defaults.plannedMinutes)
                saved["scheduleGeometry"]=encodedGeometry
                saved["scheduleRaw"]=text;saved["scheduleWeek"]=extraction.week?.toString()?:""
                saved["scheduleWeekConfirmed"]=!extraction.requiresWeekConfirmation
                setRows(extraction.rows.map{ReviewRow(it)})
                expected=null
                if(text.isBlank())message.value="No text recognized. Choose a clearer image or add shifts manually."
            } catch(e:CancellationException){throw e}
            catch(_:Exception){message.value="Could not read this image. Try a clearer screenshot."}
            finally{busy.value=false}
        }
    }
    fun confirmWeek(date:LocalDate) {
        if(date.dayOfWeek!=DayOfWeek.SATURDAY){message.value="Choose the Saturday printed for this workweek.";return}
        viewModelScope.launch {
            val defaults=planning.first().plannedMinutes
            val extraction=decodeScheduleGeometry(geometry.value)?.let{extractPassportSchedule(it,date,defaults)}?:extractSchedule(raw.value,date,defaults)
            saved["scheduleWeek"]=date.toString();saved["scheduleWeekConfirmed"]=true
            setRows(extraction.rows.map{ReviewRow(it)})
            message.value="Week confirmed. Recognized rows were refreshed for this week; review before importing."
        }
    }
    fun setRows(rows:List<ReviewRow>) { saved["scheduleRows"]=JSONArray().apply{rows.forEach{put(it.json())}}.toString() }
    fun save(rows:List<ReviewRow>,done:()->Unit) {
        if(busy.value)return
        busy.value=true;message.value=null
        viewModelScope.launch {
            try {
                val week=LocalDate.parse(weekText.value);require(confirmed.value){"Confirm the workweek first."}
                val snapshot=requireNotNull(expected){"Review the schedule before importing."}
                val selected=rows.filter{it.selected && !it.candidate.off}
                val choices=selected.map { row ->
                    require(row.reviewed || row.candidate.confidence==Confidence.HIGH){"Acknowledge uncertain rows."}
                    val shift=requireNotNull(row.candidate.shift()){"Correct all selected fields."}
                    val match=classify(shift,snapshot)
                    val replacing=if(row.replace)match.existing.singleOrNull() else null
                    require(match.kind!=ImportKind.POSSIBLE_UPDATE || replacing!=null){"Choose Keep existing or Replace schedule."}
                    ScheduleImportChoice(shift,replacing)
                }
                val result=app.repository.importSchedule(choices,snapshot,week)
                val conflicts=rows.count{!it.selected && classify(it.candidate.shift(),snapshot).kind in listOf(ImportKind.CONFLICT,ImportKind.POSSIBLE_UPDATE)}
                message.value="Imported ${result.imported} · Updated ${result.updated} · Skipped duplicates ${result.duplicates} · Skipped conflicts $conflicts · Failed 0" + if(result.refreshDeferred) " · Reminder refresh deferred; reopen the app to retry." else ""
                setRows(emptyList());saved["scheduleRaw"]="";saved["scheduleGeometry"]="";expected=null;done()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){message.value="Nothing imported. ${e.message}";expected=null}
            finally{busy.value=false}
        }
    }
}
data class ReviewRow(val candidate:ScheduleCandidate,val selected:Boolean=false,val reviewed:Boolean=false,val replace:Boolean=false) {
    fun json()=JSONObject().apply {
        put("raw",candidate.raw);put("date",candidate.date?.toString());put("start",candidate.start?.toString());put("end",candidate.end?.toString())
        put("lunch",candidate.lunch);put("source",candidate.lunchSource.name);put("net",candidate.netMinutes);put("weekday",candidate.weekday?.value)
        put("off",candidate.off);put("warnings",JSONArray(candidate.warnings));put("selected",selected);put("reviewed",reviewed);put("replace",replace)
    }
}
fun reviewRows(value:String):List<ReviewRow> = runCatching {
    val a=JSONArray(value);(0 until a.length()).map { i -> val o=a.getJSONObject(i)
        fun str(k:String)=o.optString(k).takeIf{it.isNotEmpty() && it!="null"}
        ReviewRow(ScheduleCandidate(o.getString("raw"),str("date")?.let(LocalDate::parse),str("start")?.let(LocalTime::parse),str("end")?.let(LocalTime::parse),
            str("lunch")?.toInt(),LunchSource.valueOf(o.getString("source")),str("net")?.toInt(),str("weekday")?.toInt()?.let(DayOfWeek::of),o.getBoolean("off"),
            o.getJSONArray("warnings").let{w->(0 until w.length()).map{w.getString(it)}}),o.getBoolean("selected"),o.getBoolean("reviewed"),o.getBoolean("replace"))
    }
}.getOrDefault(emptyList())
