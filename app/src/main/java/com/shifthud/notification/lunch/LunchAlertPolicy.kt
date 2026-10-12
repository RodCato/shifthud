package com.shifthud.notification.lunch

import com.shifthud.domain.model.*
import java.time.*

data class LunchAlertSettings(val minimum:Boolean=true,val planned:Boolean=false,val snoozeMinutes:Int=10,val repeat:Boolean=false) {
    init { require(snoozeMinutes in 1..120) }
}
enum class LunchAlertKind { MINIMUM, PLANNED, SNOOZE }
data class LunchAlertLedger(val identity:String,val minimumDone:Boolean=false,val plannedDone:Boolean=false,
    val snoozeAt:Instant?=null,val generation:Long=0,val receipt:String="",val minimumSent:Boolean=false)
data class LunchAlertEvent(val kind:LunchAlertKind,val receipt:String,val minutes:Int)
data class LunchAlertDecision(val ledger:LunchAlertLedger?,val event:LunchAlertEvent?,val next:Instant?,val cancel:Boolean=false)
fun lunchIdentity(session:WorkSession?) = session?.takeIf { it.state==ShiftState.ON_LUNCH && it.clockOut==null && it.lunchStart!=null }
    ?.let { "${it.id}:${it.lunchStart!!.toEpochMilli()}:${it.correctionRevision}" }

/** Missed alerts expire after ten minutes; old lunches never replay a historical alert on recovery. */
fun lunchAlertDecision(session:WorkSession?,settings:LunchAlertSettings,plannedMinutes:Int,old:LunchAlertLedger?,now:Instant):LunchAlertDecision {
    val identity=lunchIdentity(session) ?: return LunchAlertDecision(null,null,null,true)
    val start=session!!.lunchStart!!
    if(now<start || Duration.between(start,now)>Duration.ofHours(24)) return LunchAlertDecision(null,null,null,true)
    val minimum=start.plusSeconds(31*60)
    val planned=start.plusSeconds(plannedMinutes.coerceAtLeast(31)*60L)
    var state=old?.takeIf{it.identity==identity} ?: LunchAlertLedger(identity,
        minimumDone=now>minimum,plannedDone=now>planned)
    // Disabled or stale boundaries are consumed, never replayed after enabling or reboot.
    if(!settings.minimum || now>minimum.plusSeconds(600)) state=state.copy(minimumDone=true)
    if(plannedMinutes==31 && state.minimumSent) state=state.copy(plannedDone=true)
    if(!settings.planned || now>planned.plusSeconds(600)) state=state.copy(plannedDone=true)
    if(state.snoozeAt?.let{now>it.plusSeconds(600)}==true) state=state.copy(snoozeAt=null,receipt="")
    val due=when {
        state.snoozeAt?.let { now>=it }==true -> LunchAlertKind.SNOOZE
        !state.minimumDone && now>=minimum -> LunchAlertKind.MINIMUM
        !state.plannedDone && now>=planned -> LunchAlertKind.PLANNED
        else -> null
    }
    val event=due?.let { LunchAlertEvent(it,"$identity:${it.name}:${state.generation}",if(it==LunchAlertKind.MINIMUM)31 else plannedMinutes) }
    val next=listOfNotNull(minimum.takeUnless{state.minimumDone},planned.takeUnless{state.plannedDone},state.snoozeAt).minOrNull()
    return LunchAlertDecision(state,event,next,old?.identity!=identity)
}
fun acknowledgeLunchAlert(state:LunchAlertLedger,event:LunchAlertEvent,settings:LunchAlertSettings,plannedMinutes:Int,now:Instant):LunchAlertLedger = when(event.kind) {
    LunchAlertKind.MINIMUM -> state.copy(minimumDone=true,minimumSent=true,plannedDone=state.plannedDone || plannedMinutes==31,receipt=event.receipt)
    LunchAlertKind.PLANNED -> state.copy(plannedDone=true,minimumDone=true,receipt=event.receipt)
    LunchAlertKind.SNOOZE -> state.copy(snoozeAt=if(settings.repeat)now.plusSeconds(settings.snoozeMinutes*60L) else null,generation=state.generation+1,receipt=event.receipt)
}
fun snoozeLunchAlert(session:WorkSession?,state:LunchAlertLedger?,receipt:String,minutes:Int,now:Instant):LunchAlertLedger? {
    require(minutes in 1..120)
    if(state==null || state.identity!=lunchIdentity(session) || state.receipt.isEmpty() || state.receipt!=receipt || now<session!!.lunchStart || Duration.between(session.lunchStart,now)>Duration.ofHours(24)) return null
    return state.copy(snoozeAt=now.plusSeconds(minutes*60L),generation=state.generation+1,receipt="")
}
fun lunchAlertText(kind:LunchAlertKind,minutes:Int):Pair<String,String> = when(kind) {
    LunchAlertKind.MINIMUM -> "ShiftHUD — Lunch Minimum Reached" to "Your 31-minute lunch minimum is complete."
    LunchAlertKind.PLANNED -> "ShiftHUD — Planned Lunch Reached" to "Your planned $minutes-minute lunch is complete."
    LunchAlertKind.SNOOZE -> "ShiftHUD — Lunch Reminder" to "Your snooze is over. Lunch is still running."
}
