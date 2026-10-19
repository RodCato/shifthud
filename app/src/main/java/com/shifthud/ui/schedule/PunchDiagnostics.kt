package com.shifthud.ui.schedule

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.ui.time.decimalTime
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PunchDiagnostics(val scheduled: String, val sessions: List<List<String>>, val totals: List<String>, val precisionNote: String)

/** Read-only diagnostics; all duration components come from the existing engine. */
fun punchDiagnostics(shift: ScheduledShift, records: List<WorkSession>, now: Instant, zone: ZoneId, locale: Locale): PunchDiagnostics? {
    val linked=records.filter { it.scheduledShiftId==shift.id }.sortedBy { it.clockIn }
    if(linked.isEmpty()) return null
    fun stamp(value: Instant?): String = value?.atZone(zone)?.let {
        it.format(DateTimeFormatter.ofPattern(if(it.toLocalDate()==shift.date) "HH:mm:ss" else "MMM d, uuuu HH:mm:ss",locale))
    } ?: "Not recorded"
    fun duration(value: Duration): String = String.format(locale,"%02d:%02d:%02d",value.toHours(),value.toMinutes()%60,value.seconds%60)
    val results=linked.map { ShiftEngine().durations(it,1,now) }
    fun components(store: Duration, lunch: Duration, paid: Duration)=listOf(
        "Store duration: ${duration(store)}", "Unpaid lunch: ${duration(lunch)}",
        "Actual paid duration: ${duration(paid)}", "Decimal paid hours: ${decimalTime(paid,locale)}")
    val sessions=linked.mapIndexed { index,s -> buildList {
        add("Session ${index+1} · ACTUAL RECORDED PUNCHES")
        add("Clock in: ${stamp(s.clockIn)}")
        add("Lunch out: ${stamp(s.lunchStart)}")
        add("Lunch in: ${stamp(s.lunchEnd)}")
        add("Clock out: ${stamp(s.clockOut)}")
        if(s.clockOut==null) add("In progress · durations as of ${stamp(now)}")
        if(s.lunchStart!=null) add("Lunch ${index+1}: ${stamp(s.lunchStart)} – ${s.lunchEnd?.let { stamp(it) } ?: "In progress"}")
        val d=results[index]
        addAll(components(d.store,d.lunch,d.paid))
    } }
    val store=results.fold(Duration.ZERO) { a,d -> a.plus(d.store) }
    val lunch=results.fold(Duration.ZERO) { a,d -> a.plus(d.lunch) }
    val paid=results.fold(Duration.ZERO) { a,d -> a.plus(d.paid) }
    val subsecond=linked.any { listOfNotNull(it.clockIn,it.clockOut,it.lunchStart,it.lunchEnd).any { p -> p.nano!=0 } }
    return PunchDiagnostics("Scheduled: ${stamp(shift.start.atZone(zone).toInstant())} – ${stamp(shift.end.atZone(zone).toInstant())}",sessions,
        components(store,lunch,paid),buildString {
            append("Overview times show minutes; diagnostics show seconds. Calculations retain full precision.")
            if(subsecond) append(" Subsecond timestamps exist: displayed seconds omit fractions, so displayed components may differ by a second from the displayed paid result.")
            if(store.minus(lunch)!=paid) append(" Diagnostic reconciliation mismatch: store minus lunch differs from authoritative paid duration.")
        })
}
