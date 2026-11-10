package com.shifthud.frozen

import java.time.DayOfWeek
import java.time.LocalDate

val FROZEN_CARRYOVER_BASELINE:LocalDate=LocalDate.of(2026,11,1)
const val DAILY_PREFIX="daily:"
fun dailyWorkflowId(date:LocalDate)="$DAILY_PREFIX$date"
fun dailyFrozenTemplate(date:LocalDate,scheduled:Boolean=false):List<FrozenTask> {
    val base=when(date.dayOfWeek) {
    DayOfWeek.FRIDAY -> fridayFrozenTemplate(date)
    DayOfWeek.SUNDAY -> sundayFrozenTemplate(date)
    DayOfWeek.MONDAY -> if(date==FROZEN_MONDAY) mondayFrozenTemplate() else mondayFrozenTemplate().mapIndexed { index,task ->
        task.copy(id="monday-$date-${index+1}",date=date,workflows=listOf(dailyWorkflowId(date)),section="TODAY'S RESPONSIBILITIES")
    }
    DayOfWeek.TUESDAY -> tuesdayFrozenTemplate(date).map { it.copy(workflows=listOf(dailyWorkflowId(date))) }
    DayOfWeek.WEDNESDAY -> wednesdayFrozenTemplate(date).map { it.copy(workflows=listOf(dailyWorkflowId(date))) }
    else -> emptyList() // No operational procedures are invented for undefined weekdays.
}
    return base+frozenChecklistRefinements(date,scheduled,base.isNotEmpty())
}

val FROZEN_REFINEMENTS_START:LocalDate=LocalDate.of(2026,11,9)
val FrozenTask.optionalTraining:Boolean get()=id=="optional-training-$date"
fun frozenChecklistRefinements(date:LocalDate,scheduled:Boolean,hasChecklist:Boolean):List<FrozenTask> {
    if(date<FROZEN_REFINEMENTS_START || !hasChecklist) return emptyList()
    fun task(key:String,title:String,description:String,section:String="TODAY'S RESPONSIBILITIES")=
        FrozenTask("$key-$date",date,title,description,workflows=listOf(dailyWorkflowId(date)),section=section)
    return buildList {
        if(date.dayOfWeek==DayOfWeek.MONDAY) add(task("outgoing-endcap","Manage Outgoing Endcap Inventory",
            "Before Tuesday's endcap reset, avoid unnecessarily replenishing outgoing promotional merchandise. Keep the display presentable and shoppable, but limit refills to small quantities when appropriate."))
        if(date.dayOfWeek in listOf(DayOfWeek.MONDAY,DayOfWeek.TUESDAY)) add(task("endcap-first","Check Endcap Before Opening Ad Cases",
            "When replenishing a home shelf for an item also displayed on the outgoing endcap, check the endcap first. If it has surplus merchandise, transfer appropriate quantities to the home shelf before opening another case from the ad wall. Maintain adequate product availability and a presentable promotional display; do not strip the endcap unnecessarily."))
        if(scheduled) add(task("optional-training","Check Available CBTs / GRS Training",
            "When primary Frozen responsibilities are under control, check for available computer-based training modules. Complete one or more if time and staffing permit.","OPTIONAL · LOW PRIORITY"))
    }
}
/** Identity mappings only; editable titles and OCR product names never imply equivalence. */
internal fun dailyTaskDefinition(task:FrozenTask):String {
    if(task.id=="endcap-first-${task.date}") return "endcap-first"
    if(task.id=="outgoing-endcap-${task.date}") return "outgoing-endcap"
    val friday=fridayFrozenTemplate(task.date).indexOfFirst {it.id==task.id}
    if(friday>=0) return when(friday) {2->"run-backstock";6->"consolidate-backstock";else->"friday-step-$friday"}
    val sunday=sundayFrozenTemplate(task.date).indexOfFirst { it.id==task.id }
    if(sunday>=0) return when(sunday) { 1->"replenish-ad-wall";2->"run-backstock";else->"sunday-step-$sunday" }
    val monday=mondayFrozenTemplate().indices.firstOrNull { index ->
        task.id==mondayFrozenTemplate()[index].id || task.id=="monday-${task.date}-${index+1}"
    } ?: -1
    if(monday>=0) return when(monday) { 0->"inspect-freezer";2->"run-backstock";3->"replenish-ad-wall";6->"random-counts";else->"monday-step-$monday" }
    if(task.id=="tuesday-${task.date}-MIDSHIFT-0") return "run-backstock"
    frozenTruckTaskType(task)?.let { return it }
    val tuesday=tuesdayFrozenTemplate(task.date).indexOfFirst { it.id==task.id }
    return if(tuesday>=0) "tuesday-step-$tuesday" else "task:${task.id}"
}
internal fun removedFromCarryover(task:FrozenTask):Boolean = task.optionalTraining || task.resolvedByOtherAt!=null || task.carryoverArchived || task.carryoverExcluded || (task.state==FrozenState.TODO &&
    task.history.takeLast(2).map { it.state }==listOf(FrozenState.CARRY_OVER,FrozenState.TODO))
internal fun unfinishedForDate(task:FrozenTask,date:LocalDate)=(date<FROZEN_CARRYOVER_BASELINE || task.date>=FROZEN_CARRYOVER_BASELINE) && task.date<date && task.state!=FrozenState.COMPLETED && !removedFromCarryover(task)

data class FrozenDailyRow(val task:FrozenTask,val sources:List<FrozenTask>)
fun dailyFrozenRows(tasks:List<FrozenTask>,date:LocalDate):List<FrozenDailyRow> {
    val workflow=dailyWorkflowId(date)
    val selected=tasks.filter { it.date==date || (workflow in it.workflows && !removedFromCarryover(it)) || unfinishedForDate(it,date) }
        .filter { it.date<=date && (date<FROZEN_CARRYOVER_BASELINE || (it.date>=FROZEN_CARRYOVER_BASELINE && !it.carryoverArchived && !it.carryoverExcluded && it.resolvedByOtherAt==null)) }.distinctBy { it.id }
    return selected.groupBy(::dailyTaskDefinition).values.map { sources ->
        val unfinished=sources.filter { it.state!=FrozenState.COMPLETED }
        val candidates=unfinished.ifEmpty { sources }
        val representative=candidates.firstOrNull { it.date==date } ?: candidates.minWith(compareBy<FrozenTask> { it.date }.thenBy { it.id })
        val carry=unfinished.any { it.date<date }
        FrozenDailyRow(representative.copy(state=when { unfinished.isEmpty()->FrozenState.COMPLETED;carry->FrozenState.CARRY_OVER;else->representative.state }),sources)
    }
}

/** A rendered action is valid only for these exact source records, including history.
 * No new persisted revision or widget copy is needed. */
fun FrozenDailyRow.actionRevision():String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(sources.sortedBy {it.id}.toString().toByteArray(Charsets.UTF_8))
    .joinToString("") {"%02x".format(it)}
