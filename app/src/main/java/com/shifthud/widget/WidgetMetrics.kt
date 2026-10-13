package com.shifthud.widget

import com.shifthud.domain.model.*
import com.shifthud.domain.planner.PlannerSettings
import com.shifthud.domain.weekly.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.domain.pay.PayEstimator
import java.time.*

/** Presentation thresholds only; the minimum matches the existing C.3 alert rule. */
object WidgetThresholds {
    const val LUNCH_MINIMUM_MINUTES=31L
    const val EXTENDED_AFTER_MINUTES=5L
    const val APPROACHING_FRACTION=0.75
    const val NEAR_TARGET_MINUTES=120L
}
enum class WidgetTone(val argb:Long) {
    GREEN(0xFF9DD5AA), BLUE(0xFF9CCAFF), AMBER(0xFFFFD580), ORANGE(0xFFFFB77D), RED(0xFFFFA4A4)
}
data class WidgetLunch(val elapsed:Duration,val label:String,val tone:WidgetTone,val startedAt:Instant)
data class WidgetWeekly(val paid:String,val target:String,val detail:String,val fraction:Float,val tone:WidgetTone)
fun widgetLunch(session:WorkSession?,plannedMinutes:Int,now:Instant):WidgetLunch? {
    val start=session?.lunchStart ?: return null
    if(session.state!=ShiftState.ON_LUNCH || session.lunchEnd!=null)return null
    val elapsed=Duration.between(start,now).coerceAtLeast(Duration.ZERO)
    val minimum=Duration.ofMinutes(WidgetThresholds.LUNCH_MINIMUM_MINUTES)
    val planned=Duration.ofMinutes(maxOf(plannedMinutes.toLong(),WidgetThresholds.LUNCH_MINIMUM_MINUTES))
    val (label,tone)=when {
        elapsed<minimum -> "Minimum in ${((minimum.minus(elapsed).seconds+59)/60)}m" to WidgetTone.AMBER
        elapsed>=planned.plusMinutes(WidgetThresholds.EXTENDED_AFTER_MINUTES) -> "Extended lunch" to WidgetTone.ORANGE
        elapsed>=planned -> "Planned lunch reached" to WidgetTone.BLUE
        else -> "Minimum reached" to WidgetTone.GREEN
    }
    return WidgetLunch(elapsed,label,tone,start)
}
fun widgetWeekly(progress:WeeklyTargetProgress):WidgetWeekly {
    val tone=when {
        progress.paid>progress.target -> WidgetTone.RED
        progress.paid==progress.target -> WidgetTone.GREEN
        progress.remaining<=Duration.ofMinutes(WidgetThresholds.NEAR_TARGET_MINUTES) -> WidgetTone.AMBER
        progress.progress>=WidgetThresholds.APPROACHING_FRACTION -> WidgetTone.BLUE
        else -> WidgetTone.GREEN
    }
    val detail=when {
        progress.paid>progress.target -> "Over target · ${progress.paid.minus(progress.target).widgetDuration()} · Approval required"
        progress.paid==progress.target -> "Target reached"
        else -> "${when(tone) { WidgetTone.BLUE->"Approaching target · ";WidgetTone.AMBER->"Near target · ";else->"" }}${progress.remaining.widgetDuration()} remaining"
    }
    return WidgetWeekly(progress.paid.widgetDuration(),progress.target.widgetDuration(),detail,progress.progress.coerceIn(0.0,1.0).toFloat(),tone)
}
fun WidgetState.withMetrics(sessions:List<WorkSession>,schedule:List<ScheduledShift>,planning:PlannerSettings,
    weeklySettings:WeeklyTargetSettings,now:Instant,zone:ZoneId):WidgetState {
    val live=sessions.firstOrNull { it.state==ShiftState.ON_LUNCH }
    val lunch=widgetLunch(live,planning.weeklyMinutes((live?.clockIn ?: now).atZone(zone).toLocalDate()),now)
    val today=now.atZone(zone).toLocalDate()
    // Reuse the authoritative estimator's session paid durations; lunch remains unpaid.
    val estimator=PayEstimator(ShiftEngine())
    val paid=estimator.breakdown(sessions.filter { it.clockIn<=now && it.clockIn.atZone(zone).toLocalDate()==today },emptyList(),now,zone).paid
    return copy(lunchMetric=lunch,todayPaid=paid.widgetDuration(),weekly=if(weeklySettings.enabled)
        widgetWeekly(weeklyTarget(sessions,schedule,weeklySettings,planning.weeklyMinutes(today),now,zone)) else null)
}
