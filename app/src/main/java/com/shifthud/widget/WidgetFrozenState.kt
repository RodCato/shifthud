package com.shifthud.widget

import com.shifthud.frozen.*
import java.time.LocalDate

data class WidgetFrozenTask(val id:String,val title:String,val completed:Boolean) {
    val label get()="${if(completed) "✓" else "•"} $title"
}

/** Reserve the overflow link before accepting rows. Never omit tasks silently. */
fun visibleWidgetTaskCount(heights:List<Float>,available:Float,overflowHeight:Float):Int {
    if(heights.sum()<=available) return heights.size
    var used=overflowHeight
    return heights.takeWhile { height -> used+=height; used<=available }.size
}

data class WidgetFrozenState(val date:LocalDate,val completed:Int=0,val total:Int=0,
    val tasks:List<WidgetFrozenTask> = emptyList(),val hasTemplate:Boolean=false,
    val floatCount:Long?=null,val error:String?=null) {
    val summary get()=error ?: when {
        total>0 && completed==total -> "All $total tasks complete"
        total>0 -> "$completed of $total completed"
        !hasTemplate -> "No recurring tasks or active carryovers"
        else -> "No active tasks"
    }
}
fun widgetFrozenState(tasks:List<FrozenTask>,counts:FloatCounts,date:LocalDate):WidgetFrozenState {
    val rows=dailyFrozenRows(tasks,date)
    return WidgetFrozenState(date,rows.count {it.task.state==FrozenState.COMPLETED},rows.size,
        rows.sortedBy {when(it.task.state) {FrozenState.CARRY_OVER->0; FrozenState.COMPLETED->2; else->1}}
            .map {WidgetFrozenTask(it.task.id,it.task.title,it.task.state==FrozenState.COMPLETED)},
        dailyFrozenTemplate(date).isNotEmpty(),counts[date])
}
