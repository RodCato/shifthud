package com.shifthud.ui.schedule

import com.shifthud.domain.model.ScheduledShift
import com.shifthud.domain.model.WorkSession
import java.time.*
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** Display only. Multiple sessions stay separate; no same-date inference or interval bridging. */
data class SchedulePunches(val primary: String, val scheduled: String? = null, val sessions: List<String> = emptyList())

fun schedulePunches(shift: ScheduledShift, sessions: List<WorkSession>?, zone: ZoneId,
    locale: Locale, use24Hour: Boolean): SchedulePunches {
    fun stamp(value: Instant): String {
        val local=value.atZone(zone)
        val builder=DateTimeFormatterBuilder()
        if(local.toLocalDate()!=shift.date) builder.appendPattern("MMM d, ")
        builder.appendPattern(if(use24Hour) "HH:mm" else "h:mm")
        if(local.second!=0 || local.nano!=0) builder.appendPattern(":ss")
        if(local.nano!=0) builder.appendFraction(ChronoField.NANO_OF_SECOND,0,9,true)
        if(!use24Hour) builder.appendPattern(" a")
        return local.format(builder.toFormatter(locale))
    }
    val start=shift.start.atZone(zone).toInstant()
    val end=shift.end.atZone(zone).toInstant()
    val scheduled="Scheduled: ${stamp(start)} – ${stamp(end)}"
    if(sessions==null) return SchedulePunches("Loading recorded times…",scheduled)
    val linked=sessions.filter { it.scheduledShiftId==shift.id }.sortedBy { it.clockIn }
    if(linked.isEmpty()) return SchedulePunches(scheduled)
    fun activeLabel(session: WorkSession)=if(session.lunchStart!=null && session.lunchEnd==null) "ON LUNCH" else "WORKING"
    fun clocked(session: WorkSession): String = session.clockOut?.let {
        if(it<session.clockIn) "Clocked interval unavailable" else "CLOCKED: ${stamp(session.clockIn)} – ${stamp(it)}"
    } ?: "CLOCKED IN: ${stamp(session.clockIn)} · ${activeLabel(session)}"
    val single=linked.singleOrNull()
    if(single!=null) return SchedulePunches(clocked(single),scheduled.takeUnless { single.clockIn==start && single.clockOut==end })
    val active=linked.filter { it.clockOut==null }
    val primary=buildString {
        append("CLOCKED: ${linked.size} sessions · Expand for times")
        active.forEach { append("\n${activeLabel(it)} · Clocked in ${stamp(it.clockIn)}") }
    }
    return SchedulePunches(primary,scheduled,linked.mapIndexed { index,session -> "Session ${index+1} · ${clocked(session)}" })
}
