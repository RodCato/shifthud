package com.shifthud.widget

import com.shifthud.frozen.*
import java.time.LocalDate

data class WidgetFrozenState(val date:LocalDate,val completed:Int=0,val total:Int=0,
    val unfinished:List<String> = emptyList(),val hasTemplate:Boolean=false,
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
        rows.filter {it.task.state!=FrozenState.COMPLETED}.sortedBy {if(it.task.state==FrozenState.CARRY_OVER)0 else 1}.take(3).map {it.task.title},
        dailyFrozenTemplate(date).isNotEmpty(),counts[date])
}
