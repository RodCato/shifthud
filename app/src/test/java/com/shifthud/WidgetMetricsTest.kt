package com.shifthud

import com.shifthud.widget.*
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.PlannerSettings
import com.shifthud.domain.weekly.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.lunch.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class WidgetMetricsTest {
    private val start=Instant.parse("2026-10-13T08:00:00Z")
    private val session=WorkSession(1,clockIn=start.minusSeconds(4*3600),lunchStart=start,state=ShiftState.ON_LUNCH)
    private fun lunch(seconds:Long,planned:Int=60)=widgetLunch(session,planned,start.plusSeconds(seconds))!!
    @Test fun minimumUsesActualTimestampAndRoundsCountdownUp() {
        assertEquals("Minimum in 5m",lunch(26*60).label)
        assertEquals(WidgetTone.AMBER,lunch(31*60-1).tone)
        assertEquals("Minimum in 1m",lunch(31*60-1).label)
        assertEquals(WidgetTone.GREEN,lunch(31*60).tone)
        assertEquals("Minimum reached",lunch(31*60+1).label)
        val decision=lunchAlertDecision(session,LunchAlertSettings(),60,LunchAlertLedger(lunchIdentity(session)!!),start.plusSeconds(31*60))
        assertEquals(LunchAlertKind.MINIMUM,decision.event!!.kind)
    }
    @Test fun plannedAndExtendedPrecedenceIsDeterministic() {
        assertEquals(WidgetTone.GREEN,lunch(48*60-1,48).tone)
        assertEquals("Planned lunch reached",lunch(48*60,48).label)
        assertEquals(WidgetTone.BLUE,lunch(53*60-1,48).tone)
        assertEquals("Extended lunch",lunch(53*60,48).label)
        assertEquals(WidgetTone.ORANGE,lunch(54*60,48).tone)
        assertEquals(WidgetTone.BLUE,lunch(31*60,31).tone)
    }
    @Test fun noLunchColorAfterLunchOrClockOut() {
        assertNull(widgetLunch(session.copy(lunchEnd=start.plusSeconds(3600),state=ShiftState.WORKING),60,start.plusSeconds(4000)))
        assertNull(widgetLunch(session.copy(lunchEnd=start.plusSeconds(3600),clockOut=start.plusSeconds(4000),state=ShiftState.COMPLETE),60,start.plusSeconds(5000)))
    }
    private fun progress(minutes:Long)=widgetWeekly(weeklyTarget(listOf(WorkSession(2,clockIn=start,clockOut=start.plusSeconds(minutes*60),state=ShiftState.COMPLETE)),emptyList(),WeeklyTargetSettings(),60,start.plusSeconds(minutes*60),ZoneOffset.UTC))
    @Test fun weeklyThresholdsUseAuthoritativePaidHours() {
        assertEquals(WidgetTone.GREEN,progress(1799).tone)
        assertEquals(WidgetTone.BLUE,progress(1800).tone)
        assertEquals(WidgetTone.BLUE,progress(2279).tone)
        assertEquals(WidgetTone.AMBER,progress(2280).tone)
        assertEquals(WidgetTone.GREEN,progress(2400).tone)
        assertEquals("Target reached",progress(2400).detail)
        assertEquals(WidgetTone.RED,progress(2401).tone)
        assertTrue(progress(2401).detail.contains("Approval required"))
        assertEquals(1f,progress(2401).fraction)
    }
    @Test fun paidHoursPauseAndResumeAndWeeklyPreferencesAreRead() {
        val factory=WidgetStateFactory(ShiftEngine())
        fun model(s:WorkSession,now:Instant)=factory.create(emptyList(),s,360,now,ZoneOffset.UTC,Locale.US)
            .withMetrics(listOf(s),emptyList(),PlannerSettings(plannedMinutes=48),WeeklyTargetSettings(),now,ZoneOffset.UTC)
        assertEquals("4h 0m",model(session,start.plusSeconds(31*60)).todayPaid)
        assertEquals("Planned lunch reached",model(session,start.plusSeconds(48*60)).lunchMetric!!.label)
        val ended=session.copy(lunchEnd=start.plusSeconds(48*60),state=ShiftState.WORKING)
        assertEquals("4h 1m",model(ended,start.plusSeconds(49*60)).todayPaid)
        assertEquals(WidgetOperation.END_LUNCH,model(session,start.plusSeconds(31*60)).command!!.operation)
        assertNull(model(ended,start.plusSeconds(49*60)).lunchMetric)
    }
    @Test fun restartAndRebootRecomputeFromSamePersistedPunches() {
        val now=start.plusSeconds(31*60)
        assertEquals(widgetLunch(session,60,now),widgetLunch(session.copy(),60,now))
        assertEquals(Duration.ofMinutes(31),widgetLunch(session,60,now)!!.elapsed)
    }
    @Test fun colorsMeetNormalTextContrastAgainstWidgetBackground() {
        fun luminance(rgb:Long):Double {
            val parts=listOf(16,8,0).map { shift -> ((rgb shr shift) and 255)/255.0 }.map { if(it<=0.04045) it/12.92 else Math.pow((it+0.055)/1.055,2.4) }
            return parts[0]*.2126+parts[1]*.7152+parts[2]*.0722
        }
        WidgetTone.entries.forEach { assertTrue(it.name,(luminance(it.argb)+.05)/(luminance(0x15221A)+.05)>=4.5) }
    }
}
