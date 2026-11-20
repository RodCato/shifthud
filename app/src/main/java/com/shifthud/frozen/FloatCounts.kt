package com.shifthud.frozen

import com.shifthud.domain.pay.workWeekFor
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/** Counts are keyed by local calendar date, never by shift session or template. */
data class FloatCounts(val days:Map<LocalDate,Long> = emptyMap()) {
    operator fun get(date:LocalDate)=days[date] ?: 0L
    fun week(date:LocalDate):Long { val week=workWeekFor(date);return days.filterKeys {it>=week.start && it<=week.endInclusive}.values.sum() }
    fun month(date:LocalDate)=days.filterKeys {YearMonth.from(it)==YearMonth.from(date)}.values.sum()
}
internal fun decodeFloatCounts(raw:String?):FloatCounts {
    if(raw==null)return FloatCounts()
    val json=JSONObject(raw)
    return FloatCounts(json.keys().asSequence().associate {key->LocalDate.parse(key) to json.getLong(key).also {require(it>=0)}})
}
internal fun encodeFloatCounts(counts:FloatCounts)=JSONObject().apply {counts.days.toSortedMap().forEach {(date,count)->put(date.toString(),count)}}.toString()
