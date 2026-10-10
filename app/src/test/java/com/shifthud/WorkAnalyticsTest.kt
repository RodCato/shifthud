package com.shifthud

import com.shifthud.domain.analytics.*
import com.shifthud.domain.calendar.recordDuration
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.domain.weekly.WeeklyTargetSettings
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WorkAnalyticsTest {
    private val zone=ZoneId.of("America/Chicago")
    private val now=Instant.parse("2026-10-10T18:00:00Z")
    private val estimator=PayEstimator(ShiftEngine())
    private val week=AnalyticsPeriod.week(LocalDate.of(2026,10,3))
    private fun session(id:Long=1, date:LocalDate=week.start, seconds:Long=28800, hour:Int=4):WorkSession {
        val start=date.atTime(hour,0).atZone(zone).toInstant()
        return WorkSession(id=id,clockIn=start,clockOut=start.plusSeconds(seconds),state=ShiftState.COMPLETE)
    }
    private fun report(sessions:List<WorkSession> = emptyList(),period:AnalyticsPeriod=week,rates:List<PayRate> = DEFAULT_PAY_RATES,at:Instant=now,schedules:List<ScheduledShift> = emptyList(),target:WeeklyTargetSettings=WeeklyTargetSettings()) = workAnalytics(period,sessions,schedules,rates,at,zone,estimator,target)
    @Test fun saturdayFridayAndNavigation() { assertEquals(LocalDate.of(2026,10,3),week.start);assertEquals(LocalDate.of(2026,10,10),week.endExclusive);assertEquals(LocalDate.of(2026,9,26),week.move(-1).start);assertEquals(week,week.move(-1).move(1)) }
    @Test fun crossMonthWeekDoesNotSplit() { val p=week.move(-1);assertEquals(LocalDate.of(2026,10,3),p.endExclusive);assertEquals(7,report(period=p).days.size) }
    @Test fun crossYearWeekAndNavigation() { val p=AnalyticsPeriod.week(LocalDate.of(2027,1,1));assertEquals(LocalDate.of(2026,12,26),p.start);assertEquals(LocalDate.of(2027,1,2),p.move(1).start) }
    @Test fun monthNavigationAndLeapDays() { val p=AnalyticsPeriod.month(YearMonth.of(2024,2));assertEquals(29,report(period=p).days.size);assertEquals(LocalDate.of(2024,3,1),p.move(1).start);assertEquals(p,p.move(-1).move(1));assertEquals(28,report(period=AnalyticsPeriod.month(YearMonth.of(2025,2))).days.size) }
    @Test fun completedSessionsAndWorkedDayAverage() { val r=report(listOf(session(),session(2,week.start.plusDays(1),14400)));assertEquals(2,r.completedSessions);assertEquals(2,r.workedDays);assertEquals(Duration.ofHours(6),r.averagePaid);assertEquals(Duration.ofHours(12),r.paid);assertEquals(19200L,r.grossCents) }
    @Test fun multipleSessionsOnDayCountOneWorkedDay() { val r=report(listOf(session(seconds=7200),session(2,seconds=10800,hour=10)));assertEquals(1,r.workedDays);assertEquals(2,r.completedSessions);assertEquals(Duration.ofHours(5),r.averagePaid);assertEquals(2,r.days.first().workSessions.size) }
    @Test fun stableIdsDeduplicateRepeatedRows() { val s=session();assertEquals(Duration.ofHours(8),report(listOf(s,s)).paid) }
    @Test fun futureSchedulesNeverContributeWorkedTime() { val schedule=ScheduledShift(date=week.start,scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(14,0));val r=report(schedules=listOf(schedule));assertEquals(Duration.ZERO,r.paid);assertEquals(0,r.workedDays);assertEquals(1,r.days.first().scheduledShifts.size) }
    @Test fun activeAccrualSeparateFromCompletedAndCountedOnce() { val complete=session();val active=session(2,date=week.start.plusDays(1)).copy(clockOut=null,state=ShiftState.WORKING);val r=report(listOf(complete,active,active),at=active.clockIn.plusSeconds(3600));assertEquals(Duration.ofHours(9),r.paid);assertEquals(Duration.ofHours(8),r.completedPaid);assertEquals(Duration.ofHours(1),r.accruedPaid);assertEquals(1,r.activeSessions);assertEquals(1,r.completedSessions) }
    @Test fun activeLunchPausesPaidAccrual() { val start=session().clockIn;val s=WorkSession(id=1,clockIn=start,lunchStart=start.plusSeconds(3600),state=ShiftState.ON_LUNCH);assertEquals(Duration.ofHours(1),report(listOf(s),at=start.plusSeconds(7200)).paid) }
    @Test fun completedLunchSubtractedWithPrecision() { val s=session().let{it.copy(lunchStart=it.clockIn.plusSeconds(3600),lunchEnd=it.clockIn.plusSeconds(5401))};assertEquals(Duration.ofSeconds(28800-1801),report(listOf(s)).paid) }
    @Test fun totalsEqualEveryDayAndMonthUsesSameRecords() { val sessions=(0L..6L).map{session(it+1,week.start.plusDays(it),3600+it)};val w=report(sessions);val m=report(sessions,AnalyticsPeriod.month(YearMonth.of(2026,10)));assertEquals(w.paid,m.paid);assertEquals(w.grossCents,m.grossCents);assertEquals(w.paid,w.days.fold(Duration.ZERO){sum,d->sum.plus(d.paidDuration)});assertEquals(w.grossCents,w.days.sumOf{it.estimatedGross!!}) }
    @Test fun futureClockInsExcluded() { val s=session();assertTrue(report(listOf(s),at=s.clockIn.minusSeconds(1)).breakdown.contributions.isEmpty()) }
    @Test fun exactHalfOpenRangeExcludesAdjacentDates() { val r=report(listOf(session(),session(2,week.start.minusDays(1)),session(3,week.endExclusive)));assertEquals(listOf(1L),r.breakdown.contributions.map{it.session.id}) }
    @Test fun historicalRateAndFutureRaiseDoNotRewriteEarlierWeek() { val old=session();val rates=DEFAULT_PAY_RATES+PayRate(week.endExclusive,2000);assertEquals(12800L,report(listOf(old),rates=rates).grossCents);assertEquals(16000L,report(listOf(session(date=week.endExclusive)),week.move(1),rates).grossCents) }
    @Test fun differentEffectiveRatesWithinPeriod() { val r=report(listOf(session(seconds=3600),session(2,week.start.plusDays(1),3600)),rates=DEFAULT_PAY_RATES+PayRate(week.start.plusDays(1),1750));assertEquals(3350L,r.grossCents) }
    @Test fun perSessionCentRoundingIsDeterministic() { val a=session(seconds=1);val b=session(2,seconds=1,hour=6);val r=report(listOf(a,b));assertEquals(0L,r.grossCents);assertEquals(1L,grossCents(Duration.ofSeconds(2),1600));assertEquals(0L,r.days.first().estimatedGross) }
    @Test fun minuteDisplayDoesNotTruncatePayInput() { val r=report(listOf(session(seconds=141201)));assertEquals("39h 13m",recordDuration(r.paid));assertEquals(62756L,r.grossCents) }
    @Test fun missingHistoricalRateShowsUnavailableNotZero() { assertNull(report(listOf(session()),rates=listOf(PayRate(week.endExclusive,2000))).grossCents) }
    @Test fun overnightMonthAttributedEntirelyToClockIn() { val s=session(date=LocalDate.of(2026,9,30),seconds=14400,hour=23);assertEquals(Duration.ofHours(4),report(listOf(s),AnalyticsPeriod.month(YearMonth.of(2026,9))).paid);assertEquals(Duration.ZERO,report(listOf(s),AnalyticsPeriod.month(YearMonth.of(2026,10))).paid) }
    @Test fun dstFallBackUsesActualElapsedTimeAndClockInMonth() { val date=LocalDate.of(2026,10,31);val start=date.atTime(23,0).atZone(zone).toInstant();val end=date.plusDays(1).atTime(3,0).atZone(zone).toInstant();val s=WorkSession(id=1,clockIn=start,clockOut=end,state=ShiftState.COMPLETE);assertEquals(Duration.ofHours(5),report(listOf(s),AnalyticsPeriod.month(YearMonth.of(2026,10)),at=end).paid) }
    @Test fun dstSpringForwardUsesElapsedTime() { val date=LocalDate.of(2026,3,8);val start=date.atStartOfDay(zone).toInstant();val end=date.atTime(4,0).atZone(zone).toInstant();val s=WorkSession(id=1,clockIn=start,clockOut=end,state=ShiftState.COMPLETE);assertEquals(Duration.ofHours(3),report(listOf(s),AnalyticsPeriod.week(date)).paid) }
    @Test fun zeroDurationDayNotWorkedButSessionCountRetained() { val r=report(listOf(session(seconds=0)));assertEquals(0,r.workedDays);assertEquals(Duration.ZERO,r.averagePaid);assertEquals(1,r.completedSessions) }
    @Test fun emptyMonthHasEveryZeroDay() { val r=report(period=AnalyticsPeriod.month(YearMonth.of(2026,10)));assertEquals(31,r.days.size);assertEquals(0L,r.grossCents);assertEquals(0,r.activeSessions);assertTrue(r.days.all{it.paidDuration.isZero}) }
    @Test fun currentTargetPreferenceUsedWithoutScheduleProjection() { val r=report(listOf(session(seconds=3600)),target=WeeklyTargetSettings(targetMinutes=120));assertEquals(.5,r.targetProgress,0.0) }
    @Test fun timeZoneControlsLocalAttribution() { val s=WorkSession(id=1,clockIn=Instant.parse("2026-10-01T02:00:00Z"),clockOut=Instant.parse("2026-10-01T03:00:00Z"),state=ShiftState.COMPLETE);val oct=AnalyticsPeriod.month(YearMonth.of(2026,10));assertEquals(Duration.ZERO,report(listOf(s),oct).paid);assertEquals(Duration.ofHours(1),workAnalytics(oct,listOf(s),emptyList(),DEFAULT_PAY_RATES,now,ZoneOffset.UTC,estimator,WeeklyTargetSettings()).paid) }
}
