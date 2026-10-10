package com.shifthud

import com.shifthud.domain.calendar.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WorkCalendarTest {
    private val zone=ZoneId.of("America/Chicago")
    private val now=LocalDateTime.of(2026,10,6,18,0).atZone(zone).toInstant()
    private val month=YearMonth.of(2026,10)
    private val estimator=PayEstimator(ShiftEngine())
    private fun stamp(day:Int,hour:Int)=LocalDateTime.of(2026,10,day,hour,0).atZone(zone).toInstant()
    private fun session(id:Long=1,day:Int=3,start:Int=4,end:Int=13)=WorkSession(id=id,clockIn=stamp(day,start),clockOut=stamp(day,end),state=ShiftState.COMPLETE)
    private fun planned(day:Int=3)=ScheduledShift(date=LocalDate.of(2026,10,day),scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0))
    private fun days(sessions:List<WorkSession> = emptyList(),schedule:List<ScheduledShift> = emptyList())=workCalendar(month,schedule,sessions,DEFAULT_PAY_RATES,now,zone,estimator)
    private fun day(sessions:List<WorkSession> = emptyList(),schedule:List<ScheduledShift> = emptyList(),date:Int=3)=days(sessions,schedule).single{it.date==LocalDate.of(2026,10,date)}
    @Test fun octoberGridIncludesLeadingAndTrailingWorkWeekDays(){
        val range=calendarRange(month)
        assertEquals(LocalDate.of(2026,9,26),range.start);assertEquals(LocalDate.of(2026,11,7),range.endExclusive)
        assertEquals(42,days().size);assertEquals(42,days().map{it.date}.distinct().size)
    }
    @Test fun allMonthDaysAppearExactlyOnce(){assertEquals((1..31).toList(),days().filter{YearMonth.from(it.date)==month}.map{it.date.dayOfMonth})}
    @Test fun saturdayFirstWeekdayOrder(){assertEquals(listOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY,DayOfWeek.MONDAY,DayOfWeek.TUESDAY,DayOfWeek.WEDNESDAY,DayOfWeek.THURSDAY,DayOfWeek.FRIDAY),WORK_WEEK_DAYS);days().chunked(7).forEach{assertEquals(WORK_WEEK_DAYS,it.map{d->d.date.dayOfWeek})}}
    @Test fun shortMonthStillHasFiveRows(){val r=calendarRange(YearMonth.of(2025,2));assertEquals(35,Duration.between(r.start.atStartOfDay(),r.endExclusive.atStartOfDay()).toDays())}
    @Test fun navigationCrossesYearAndReturns(){assertEquals(YearMonth.of(2025,12),YearMonth.of(2026,1).minusMonths(1));assertEquals(month,month.minusMonths(1).plusMonths(1));assertNotEquals(calendarRange(month),calendarRange(month.plusMonths(1)))}
    @Test fun completedCreatesWorkedIndicator(){val d=day(listOf(session()));assertTrue(d.hasCompletedSession);assertEquals("●",d.indicator)}
    @Test fun activeHasPriorityOverCompleted(){val d=day(listOf(session(),WorkSession(id=2,clockIn=stamp(3,15))));assertTrue(d.hasActiveSession);assertEquals("▶",d.indicator)}
    @Test fun onLunchIsActive(){assertTrue(day(listOf(WorkSession(clockIn=stamp(3,4),lunchStart=stamp(3,9),state=ShiftState.ON_LUNCH))).hasActiveSession)}
    @Test fun scheduledOnlyNeverMeansWorked(){val d=day(schedule=listOf(planned()));assertFalse(d.hasCompletedSession);assertTrue(d.workSessions.isEmpty());assertEquals("○",d.indicator);assertEquals(Duration.ZERO,d.paidDuration)}
    @Test fun unscheduledSessionStillWorked(){val d=day(listOf(session()));assertTrue(d.hasCompletedSession);assertTrue(d.scheduledShifts.isEmpty())}
    @Test fun scheduledAndWorkedPreserveBothWithWorkedPriority(){val d=day(listOf(session()),listOf(planned()));assertEquals("●",d.indicator);assertEquals(1,d.scheduledShifts.size);assertTrue(d.stateDescription.contains("Scheduled"))}
    @Test fun todayFlagIndependentOfWork(){assertTrue(day(date=6).isToday);assertFalse(day().isToday)}
    @Test fun emptyDayHasNoShift(){val d=day();assertEquals("–",d.indicator);assertTrue(d.stateDescription.contains("No shift"))}
    @Test fun sameDaySessionsRemainSeparateAndChronological(){val d=day(listOf(session(2,3,15,18),session()));assertEquals(listOf(1L,2L),d.workSessions.map{it.id});assertEquals(2,d.breakdown.contributions.size)}
    @Test fun dailyPaidAndGrossSumSeparateSessions(){val d=day(listOf(session(2,3,15,18),session()));assertEquals(Duration.ofHours(12),d.paidDuration);assertEquals(19200L,d.estimatedGross)}
    @Test fun lunchRemovedFromDailyPaid(){val s=session().copy(lunchStart=stamp(3,9),lunchEnd=stamp(3,10));assertEquals(Duration.ofHours(8),day(listOf(s)).paidDuration)}
    @Test fun currentRangeIsOctoberThreeThroughNine(){assertEquals((3..9).toList(),days().filter{it.isCurrentWeek}.map{it.date.dayOfMonth})}
    @Test fun octoberThreeFiveSixAppearOnTheirDates(){val list=listOf(session(day=3),session(id=2,day=5),session(id=3,day=6));assertEquals(listOf(3,5,6),days(list).filter{it.hasCompletedSession}.map{it.date.dayOfMonth})}
    @Test fun overnightUsesClockInDateOnly(){val s=session().copy(clockIn=stamp(3,23),clockOut=stamp(4,7));assertEquals(listOf(3),days(listOf(s)).filter{it.hasCompletedSession}.map{it.date.dayOfMonth});assertEquals(Duration.ofHours(8),day(listOf(s)).paidDuration)}
    @Test fun futureScheduleShowsNoAttendance(){val d=day(schedule=listOf(planned(9)),date=9);assertEquals("○",d.indicator);assertFalse(d.hasCompletedSession)}
    @Test fun pastScheduleShowsNoAttendance(){val d=day(schedule=listOf(planned(2)),date=2);assertEquals("○",d.indicator);assertFalse(d.hasCompletedSession)}
    @Test fun editedPunchesRecalculateDayAndMoveDate(){val original=session();val edited=original.copy(clockIn=stamp(2,23));assertEquals(listOf(2),days(listOf(edited)).filter{it.hasCompletedSession}.map{it.date.dayOfMonth});assertEquals(Duration.ofHours(14),day(listOf(edited),date=2).paidDuration)}
    @Test fun deletionClearsWorkedUnlessAnotherSessionRemains(){assertFalse(day().hasCompletedSession);assertTrue(day(listOf(session(2,3,15,18))).hasCompletedSession)}
    @Test fun unavailableRateDoesNotHideHoursOrSession(){val d=workCalendar(month,emptyList(),listOf(session()),emptyList(),now,zone,estimator).single{it.date.dayOfMonth==3&&it.date.monthValue==10};assertEquals(Duration.ofHours(9),d.paidDuration);assertNull(d.estimatedGross);assertEquals(1,d.workSessions.size)}
    @Test fun weekBreakdownAndAggregateUseIdenticalSetAndExactRounding(){
        val list=listOf(session(),session(2,5),session(3,6),session(4,2),session(5,10))
        val breakdown=estimator.weekBreakdown(list,DEFAULT_PAY_RATES,now,zone)
        assertEquals(listOf(1L,2L,3L),breakdown.contributions.map{it.session.id})
        assertEquals(estimator.week(list,DEFAULT_PAY_RATES,now,zone),PayEstimate(breakdown.paid,breakdown.grossCents!!))
        assertEquals(breakdown.grossCents,breakdown.contributions.sumOf{it.grossCents!!})
    }
    @Test fun unexpectedDuplicatesAndZeroDurationRemainVisible(){val s=session();val b=estimator.weekBreakdown(listOf(s,s.copy(id=2),s.copy(id=3,clockOut=s.clockIn)),DEFAULT_PAY_RATES,now,zone);assertEquals(3,b.contributions.size);assertEquals(Duration.ofHours(18),b.paid)}
    @Test fun perSessionRoundingReconcilesWithTotal(){val s=session().copy(clockOut=stamp(3,4).plusMillis(1125));val b=estimator.weekBreakdown(listOf(s,s.copy(id=2)),DEFAULT_PAY_RATES,now,zone);assertEquals(listOf(1L,1L),b.contributions.map{it.grossCents});assertEquals(2L,b.grossCents);assertEquals("0m",recordDuration(b.paid))}
    @Test fun durationPresentationUsesWholeMinutes(){assertEquals("0m",recordDuration(Duration.ofMillis(1001)));assertEquals("8h 6m",recordDuration(Duration.ofMinutes(486)))}
}
