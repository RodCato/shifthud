package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import com.shifthud.domain.pay.workWeekFor
import com.shifthud.domain.payroll.hoursDifferenceLabel
import com.shifthud.notification.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ClockOutPlannerTest {
    private val zone = ZoneOffset.UTC
    private val monday = LocalDate.of(2026,10,5)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour,minute).toInstant(zone)
    private val shifts get() = (0..4).map { ScheduledShift(it+1L,monday.plusDays(it.toLong()),LocalTime.of(4,0),LocalTime.of(13,0)) }
    private val friday get() = monday.plusDays(4)
    private fun completed(lunch: Int=60) = shifts.take(4).map { WorkSession(it.id,it.id,at(it.date,4),at(it.date,8),at(it.date,8).plusSeconds(lunch*60L),at(it.date,13),ShiftState.COMPLETE) }
    private fun plan(records:List<WorkSession> = emptyList(), schedules:List<ScheduledShift> = shifts, settings:PlannerSettings=PlannerSettings(), now:Instant=at(monday,3),target:Duration=Duration.ofHours(40)) = clockOutPlan(records,schedules,target,settings,now,zone)
    @Test fun saturdayFridayBoundary() { assertEquals(LocalDate.of(2026,10,3),workWeekFor(friday).start);assertEquals(LocalDate.of(2026,10,10),workWeekFor(friday.plusDays(1)).start) }
    @Test fun exactlyFortyWithFullLunches() { val p=plan();assertEquals(Duration.ofHours(40),p.projected);assertEquals(at(friday,13),p.clockOut) }
    @Test fun shorterActualLunchesLeaveAtNoon() { assertEquals(at(friday,12),plan(completed(45),now=at(friday,3)).clockOut) }
    @Test fun shorterStrategyReservesFullFinalLunch() { val p=plan(settings=PlannerSettings(minimumMinutes=45,strategy=LunchStrategy.SHORTER));assertEquals(at(friday,12),p.clockOut);assertEquals(Duration.ofHours(41),p.projected) }
    @Test fun thirtyOneMinuteCustomLunch() { val p=plan(settings=PlannerSettings(strategy=LunchStrategy.CUSTOM,custom=shifts.associate { it.date to 31 }));assertEquals(at(friday,10,35),p.clockOut) }
    @Test fun lateStartReducesProjectionWithoutInventingExtraWork() { val active=WorkSession(5,5,at(friday,5));val p=plan(completed()+active,now=at(friday,6));assertEquals(Duration.ofHours(39),p.projected);assertNull(p.clockOut);assertTrue(p.issues.any { it.contains("short") }) }
    @Test fun earlyStartAdvancesDeparture() { val active=WorkSession(5,5,at(friday,3));assertEquals(at(friday,12),plan(completed()+active,now=at(friday,6)).clockOut) }
    @Test fun missedShiftIsNotInvented() { val p=plan(now=at(monday,14));assertEquals(Duration.ofHours(32),p.projected);assertNull(p.clockOut) }
    @Test fun addedShiftChangesFinalDay() { val base=shifts.take(4);val p=plan(schedules=base);assertNull(p.clockOut);assertEquals(at(friday,13),plan(schedules=base+shifts.last()).clockOut) }
    @Test fun editedPunchRecalculates() { val records=completed().toMutableList();records[0]=records[0].copy(clockOut=records[0].clockOut!!.plusSeconds(61),correctionRevision=1);assertEquals(at(friday,13).minusSeconds(61),plan(records,now=at(friday,3)).clockOut) }
    @Test fun activeLunchPausesAndAutomaticEndResumes() {
        val live=WorkSession(5,5,at(friday,4),at(friday,8),state=ShiftState.ON_LUNCH,autoLunchMinutes=60)
        val a=plan(completed()+live,now=at(friday,8,10));val b=plan(completed()+live,now=at(friday,8,40))
        assertEquals(a.worked,b.worked);assertEquals(at(friday,13),b.clockOut)
        val ended=live.copy(lunchEnd=at(friday,9),state=ShiftState.WORKING,lunchEndAutomatic=true)
        assertEquals(b.worked.plusMinutes(1),plan(completed()+ended,now=at(friday,9,1)).worked)
    }
    @Test fun multipleShiftsSameDayPreserveGap() {
        val day=listOf(ScheduledShift(1,monday,LocalTime.of(4,0),LocalTime.of(8,0),30),ScheduledShift(2,monday,LocalTime.of(10,0),LocalTime.of(15,0),30))
        val p=plan(schedules=day,target=Duration.ofHours(7));assertEquals(at(monday,14),p.clockOut)
    }
    @Test fun overnightUsesFollowingDay() { val s=ScheduledShift(1,friday,LocalTime.of(22,0),LocalTime.of(6,0),60);val p=plan(schedules=listOf(s),now=at(friday,20),target=Duration.ofHours(7));assertEquals(at(friday.plusDays(1),6),p.clockOut) }
    @Test fun overlapsAreNotDoubleCountedOrTrusted() { val p=plan(schedules=shifts+shifts.first().copy(id=9));assertEquals(Duration.ofHours(40),p.projected);assertNull(p.clockOut);assertTrue(p.issues.any { it.contains("Overlapping") }) }
    @Test fun noScheduleWithholdsRecommendation() { assertNull(plan(schedules=emptyList()).clockOut) }
    @Test fun exceededTargetNeverSuggestsAdditionalWork() { val p=plan(completed(),now=at(friday,3),target=Duration.ofHours(30));assertTrue(p.reached);assertNull(p.clockOut);assertEquals(Duration.ZERO,p.remaining) }
    @Test fun actualUnscheduledWorkCounts() { val records=completed()+WorkSession(99,clockIn=at(monday.plusDays(1),14),clockOut=at(monday.plusDays(1),15),state=ShiftState.COMPLETE);assertEquals(at(friday,12),plan(records,now=at(friday,3)).clockOut) }
    @Test fun duplicateSessionIdsCountOnce() { val records=completed();assertEquals(plan(records,now=at(friday,3)).worked,plan(records+records,now=at(friday,3)).worked) }
    @Test fun activeUnscheduledShiftIsQualified() { val p=plan(completed()+WorkSession(90,clockIn=at(friday,4)),now=at(friday,6));assertNull(p.clockOut);assertTrue(p.issues.any { it.contains("unscheduled") }) }
    @Test fun subMinutePrecisionRetained() { val a=WorkSession(5,5,at(friday,4).plusMillis(123));assertEquals(at(friday,13).plusMillis(123),plan(completed()+a,now=at(friday,6),schedules=shifts.map { if(it.id==5L)it.copy(scheduledEnd=LocalTime.of(14,0)) else it }).clockOut) }
    @Test fun minimumLunchIsNeverSilentlySkipped() { assertEquals(Duration.ofMinutes(30),PlannerSettings().lunch(shifts.first().copy(plannedLunchMinutes=0),friday)) }
    @Test fun reminderDedupAndClockCoincidence() { val p=plan(completed()+WorkSession(5,5,at(friday,4)),now=at(friday,11));val now=at(friday,12);val d=evaluatePlannerWarning(p,setOf(60,30,15,0),emptySet(),now,true);assertEquals(60,d.boundary);assertNull(evaluatePlannerWarning(p,setOf(60),setOf(60),now,true).boundary);assertTrue(coincidentClockOut(p.clockOut,at(friday,13)));assertFalse(coincidentClockOut(p.clockOut,at(friday,12))) }
    @Test fun reminderBaselinesPastBoundaries() { val p=plan();assertNull(evaluatePlannerWarning(p,setOf(60),null,at(friday,12),true).boundary) }
    @Test fun readableHoursDifference() { assertEquals("+24 seconds",hoursDifferenceLabel("0.006666666667".toBigDecimal()));assertEquals("-24 seconds",hoursDifferenceLabel("-0.006666666667".toBigDecimal())) }
    @Test fun warningTextIsShortAndQualified() { assertTrue(plannerWarningText(15).first.contains("15"));assertTrue(plannerWarningText(15).second.contains("manager"));assertTrue(plannerWarningText(0).first.contains("reached")) }
}
