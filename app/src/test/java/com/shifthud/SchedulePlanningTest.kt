package com.shifthud

import com.shifthud.ui.schedule.*
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import com.shifthud.domain.pay.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SchedulePlanningTest {
    private val zone=ZoneOffset.UTC
    private val week=scheduleWeek(LocalDate.of(2026,10,17))
    private val now=week.start.atStartOfDay(zone).toInstant()
    private val settings=PlannerSettings(dynamicWeekly=true).withWeek(week.start,31)
    private val shifts=(0L..4L).map { i -> ScheduledShift(i+1,week.start.plusDays(i),LocalTime.of(if(i==0L)16 else if(i==1L)10 else 8,0),LocalTime.of(if(i==0L)23 else if(i==1L)18 else 17,0),employerNetMinutes=if(i==0L)360 else if(i==1L)420 else 480) }
    private fun summary(rows:List<ScheduledShift> = shifts, records:List<WorkSession> = emptyList(), at:Instant=now, config:PlannerSettings=settings)=schedulePlanning(week,rows,records,config,Duration.ofHours(40),at,zone,DEFAULT_PAY_RATES)
    @Test fun passportFixtureUsesEmployerMetadataAndAuthoritativeProjection() {
        val result=summary();assertEquals(5,result.shiftCount);assertEquals(42*60,shifts.sumOf{it.duration(zone).toMinutes()}.toInt())
        assertEquals(Duration.ofHours(37),result.employerPaid);assertEquals(0,result.estimatedRows)
        assertEquals(Duration.ofMinutes(2365),result.plan.projected);assertEquals(Duration.ofMinutes(35),result.plan.shortfall)
        assertEquals(grossCents(result.plan.projected,1600),result.projectedGross)
    }
    @Test fun missingLunchUsesDefaultNotZero() {
        val result=summary(shifts.map{it.copy(employerNetMinutes=null)})
        assertEquals(5,result.estimatedRows);assertEquals(Duration.ofHours(37),result.employerPaid)
        assertEquals(Duration.ofMinutes(2365),result.plan.projected)
    }
    @Test fun invalidMetadataFallsBackAndMixedMetadataStaysLabelledEstimate() {
        val result=summary(shifts.mapIndexed { i,s -> if(i==0)s.copy(employerNetMinutes=1440) else s })
        assertEquals(1,result.estimatedRows);assertEquals(Duration.ofHours(37),result.employerPaid)
    }
    @Test fun emptyWeekIsUnknownEmployerHoursNotCompleteSchedule() { val s=summary(emptyList());assertNull(s.employerPaid);assertEquals(Duration.ZERO,s.plan.projected);assertEquals(Duration.ofHours(40),s.plan.shortfall) }
    @Test fun partialWeekDoesNotInventMissingDays() { val s=summary(shifts.take(1));assertEquals(1,s.shiftCount);assertEquals(Duration.ofMinutes(389),s.plan.projected) }
    @Test fun actualPunchesReplaceLinkedScheduleWithoutDoubleCounting() {
        val start=now.plusSeconds(16*3600);val record=WorkSession(10,1,start,clockOut=start.plusSeconds(5*3600),state=ShiftState.COMPLETE)
        val result=summary(records=listOf(record),at=now.plusSeconds(24*3600))
        assertEquals(Duration.ofHours(5),result.plan.worked);assertEquals(Duration.ofMinutes(2276),result.plan.projected)
    }
    @Test fun activeSessionUsesExistingPlanner() {
        val start=now.plusSeconds(16*3600);val record=WorkSession(10,1,start)
        val result=summary(records=listOf(record),at=start.plusSeconds(3600))
        assertEquals(clockOutPlan(listOf(record),shifts,Duration.ofHours(40),settings,start.plusSeconds(3600),zone,week.start),result.plan)
    }
    @Test fun historicalWeekDoesNotInventMissedWork() { val result=summary(at=now.plusSeconds(8*86400));assertEquals(Duration.ZERO,result.plan.projected);assertEquals(Duration.ofHours(37),result.employerPaid) }
    @Test fun overnightBelongsToFridayAndDeductsPlannedLunch() {
        val s=ScheduledShift(99,week.endInclusive,LocalTime.of(23,0),LocalTime.of(4,0))
        assertEquals(Duration.ofMinutes(269),summary(listOf(s)).plan.projected)
    }
    @Test fun adjacentPlansAreIndependentAndExcludedSchedulesDoNotLeak() {
        val changed=settings.withWeek(week.start.plusWeeks(1),90)
        assertEquals(summary().plan,summary(rows=shifts+shifts.map{it.copy(id=it.id+20,date=it.date.plusWeeks(1))},config=changed).plan)
        assertEquals(31,changed.weeklyMinutes(week.start));assertEquals(90,changed.weeklyMinutes(week.start.plusWeeks(1)))
    }
    @Test fun rateChangeDoesNotGuessProjectedGrossAllocation() {
        val result=schedulePlanning(week,shifts,emptyList(),settings,Duration.ofHours(40),now,zone,listOf(PayRate(LocalDate.MIN,1600),PayRate(week.start.plusDays(2),1700)))
        assertNull(result.projectedGross)
    }
}
