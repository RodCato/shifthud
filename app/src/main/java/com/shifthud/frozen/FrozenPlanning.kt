package com.shifthud.frozen

import com.shifthud.domain.model.ScheduledShift
import com.shifthud.domain.pay.workWeekFor
import java.time.*

/** Pure projection: never writes a workflow, assignment, or task. */
fun frozenPlanningRows(tasks:List<FrozenTask>,date:LocalDate,today:LocalDate,scheduled:Boolean=false):List<FrozenDailyRow> {
    if(date<=today) return dailyFrozenRows(tasks,date)
    // Do not predict unfinished work from intervening future routines.
    val known=tasks.filter {it.date<=today || it.date==date}
    val missing=dailyFrozenTemplate(date,scheduled).filter {candidate->known.none {it.id==candidate.id}}
    return dailyFrozenRows(known+missing,date)
}
fun frozenWorkflowFocus(date:LocalDate):String=when(date.dayOfWeek) {
    DayOfWeek.SUNDAY->"Frozen delivery"
    DayOfWeek.MONDAY->"Carryover & backstock"
    DayOfWeek.TUESDAY->"Endcap transition"
    DayOfWeek.WEDNESDAY->"Frozen delivery"
    DayOfWeek.FRIDAY->"Frozen delivery & KE inspection"
    else->"No defined template"
}
data class FrozenPlanningDay(val date:LocalDate,val focus:String,val hasTemplate:Boolean,
    val rows:List<FrozenDailyRow>,val scheduledShifts:List<ScheduledShift>,val preview:Boolean) {
    val completed get()=rows.count {it.task.state==FrozenState.COMPLETED}
    val outstanding get()=rows.size-completed
    val carryovers get()=rows.count {row->row.sources.any {it.date<date && it.state!=FrozenState.COMPLETED}}
    fun scheduledSpan(zone:ZoneId):Duration=scheduledShifts.fold(Duration.ZERO) {total,shift->total.plus(shift.duration(zone))}
}
fun frozenPlanningWeek(tasks:List<FrozenTask>,schedule:List<ScheduledShift>,selected:LocalDate,today:LocalDate):List<FrozenPlanningDay> {
    val start=workWeekFor(selected).start
    return (0L..6L).map {offset->
        val date=start.plusDays(offset)
        FrozenPlanningDay(date,frozenWorkflowFocus(date),dailyFrozenTemplate(date).isNotEmpty(),
            frozenPlanningRows(tasks,date,today,schedule.any {it.date==date}),schedule.filter {it.date==date}.sortedBy {it.start},date>today)
    }
}
