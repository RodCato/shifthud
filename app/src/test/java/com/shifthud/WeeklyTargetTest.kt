package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.weekly.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeeklyTargetTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val settings = WeeklyTargetSettings()
    private fun session(id: Long=1, date: String="2026-10-03", minutes: Long=480, manual: Boolean=false) = WorkSession(id=id,
        clockIn=Instant.parse("${date}T04:00:00Z"),clockOut=Instant.parse("${date}T04:00:00Z").plusSeconds(minutes*60),state=ShiftState.COMPLETE,manuallyEntered=manual)
    private fun result(records: List<WorkSession> = emptyList(), schedule: List<ScheduledShift> = emptyList(), time: Instant=now, config: WeeklyTargetSettings=settings) = weeklyTarget(records,schedule,config,60,time,zone)
    private val active = WorkSession(id=9,scheduledShiftId=9,clockIn=now.minusSeconds(4*3600))
    private val shift = ScheduledShift(9,LocalDate.of(2026,10,9),LocalTime.of(8,0),LocalTime.of(17,0),60)
    @Test fun defaultFortyHours(){ assertEquals(Duration.ofHours(40),settings.target);assertTrue(settings.enabled);assertEquals(setOf(120,30,10),settings.warnings) }
    @Test fun configurableTarget(){ assertEquals(Duration.ofMinutes(2250),result(config=settings.copy(targetMinutes=2250)).remaining) }
    @Test fun decimalInput(){ assertEquals(2250,parseWeeklyTargetMinutes("37.5"));assertEquals(2400,parseWeeklyTargetMinutes("40"));assertEquals(2250,parseWeeklyTargetMinutes("37,5")) }
    @Test fun invalidInputRejected(){ listOf("0","-1","169","word","0.001").forEach{assertTrue(runCatching{parseWeeklyTargetMinutes(it)}.isFailure)} }
    @Test fun physicalFixture3913(){ val r=result(listOf(session(minutes=2353)));assertEquals(Duration.ofMinutes(47),r.remaining);assertEquals(0.9804167,r.progress,0.00001) }
    @Test fun saturdayFridayOnly(){ val r=result(listOf(session(1,"2026-10-02"),session(2,"2026-10-03"),session(3,"2026-10-09"),session(4,"2026-10-10")));assertEquals(Duration.ofHours(16),r.paid);assertEquals(LocalDate.of(2026,10,3),r.week.start) }
    @Test fun unpaidLunchExcluded(){ val s=session(minutes=540).let{it.copy(lunchStart=it.clockIn.plusSeconds(4*3600),lunchEnd=it.clockIn.plusSeconds(5*3600))};assertEquals(Duration.ofHours(8),result(listOf(s)).paid) }
    @Test fun workingCountdown(){ val a=result(listOf(active));val b=result(listOf(active),time=now.plusSeconds(60));assertEquals(a.remaining.minusMinutes(1),b.remaining) }
    @Test fun lunchPauses(){ val s=active.copy(lunchStart=now,state=ShiftState.ON_LUNCH);assertEquals(result(listOf(s)).remaining,result(listOf(s),time=now.plusSeconds(600)).remaining) }
    @Test fun lunchResumes(){ val s=active.copy(lunchStart=now.minusSeconds(600),lunchEnd=now);assertEquals(result(listOf(s)).remaining.minusMinutes(1),result(listOf(s),time=now.plusSeconds(60)).remaining) }
    @Test fun completeAndHistoricalContribute(){ assertEquals(Duration.ofHours(16),result(listOf(session(),session(2,"2026-10-05",manual=true))).paid) }
    @Test fun deletionRecalculates(){ val a=session();val b=session(2,"2026-10-05");assertEquals(result(listOf(a,b)).paid.minusHours(8),result(listOf(b)).paid) }
    @Test fun correctionRecalculates(){ val s=session();val corrected=s.copy(clockOut=s.clockOut!!.minusSeconds(600),correctionRevision=1);assertEquals(result(listOf(s)).paid.minusMinutes(10),result(listOf(corrected)).paid) }
    @Test fun projectionClockOut(){ val r=result(listOf(session(minutes=32*60),active));assertEquals(now.plusSeconds(4*3600),r.projectedClockOut) }
    @Test fun unknownLunchNoWallClockProjection(){ val s=active.copy(lunchStart=now,state=ShiftState.ON_LUNCH);assertNull(result(listOf(s)).projectedClockOut) }
    @Test fun knownLunchEstimatedProjection(){ val s=active.copy(lunchStart=now,state=ShiftState.ON_LUNCH,autoLunchMinutes=60);val r=result(listOf(session(minutes=32*60),s));assertEquals(now.plusSeconds(5*3600),r.projectedClockOut);assertTrue(r.lunchEstimate) }
    @Test fun futureScheduleProjection(){ val r=result(schedule=listOf(shift),time=now.minusSeconds(5*3600));assertEquals(Duration.ofHours(8),r.projected) }
    @Test fun completeReplacesSchedule(){ val s=session(date="2026-10-09").copy(scheduledShiftId=9);assertEquals(Duration.ofHours(8),result(listOf(s),listOf(shift)).projected) }
    @Test fun earlyActiveWorkProjectsThroughScheduledEnd(){
        val early=active.copy(clockIn=Instant.parse("2026-10-09T07:55:00Z"))
        assertEquals(Duration.ofMinutes(485),result(listOf(early),listOf(shift),time=Instant.parse("2026-10-09T07:56:00Z")).projected)
    }
    @Test fun lateActiveWorkShortensProjection(){
        val late=active.copy(clockIn=Instant.parse("2026-10-09T08:15:00Z"))
        assertEquals(Duration.ofMinutes(465),result(listOf(late),listOf(shift)).projected)
    }
    @Test fun multipleActualSessionsReplaceOneSchedule(){
        val a=session(date="2026-10-09",minutes=120).copy(scheduledShiftId=9)
        val b=a.copy(id=2,clockIn=a.clockIn.plusSeconds(3*3600),clockOut=a.clockOut!!.plusSeconds(3*3600))
        assertEquals(Duration.ofHours(4),result(listOf(a,b),listOf(shift)).projected)
    }
    @Test fun activeNotDoubleCounted(){ val r=result(listOf(active),listOf(shift));assertEquals(Duration.ofHours(8),r.projected) }
    @Test fun plannedLunchSubtracted(){ assertEquals(Duration.ofMinutes(510),result(schedule=listOf(shift.copy(plannedLunchMinutes=30)),time=now.minusSeconds(5*3600)).projected) }
    @Test fun defaultLunchFallback(){ assertEquals(Duration.ofHours(8),result(schedule=listOf(shift.copy(plannedLunchMinutes=null)),time=now.minusSeconds(5*3600)).projected) }
    @Test fun completedShortLunchIncreasesProjection(){ val s=active.copy(lunchStart=now.minusSeconds(1800),lunchEnd=now);assertEquals(Duration.ofMinutes(510),result(listOf(s),listOf(shift)).projected) }
    @Test fun liveLunchRemainingOnly(){ val s=active.copy(lunchStart=now.minusSeconds(1800),state=ShiftState.ON_LUNCH,autoLunchMinutes=60);assertEquals(Duration.ofHours(8),result(listOf(s),listOf(shift)).projected) }
    @Test fun projectedOverage(){ val r=result(listOf(session(minutes=32*60),active.copy(lunchStart=now.minusSeconds(1800),lunchEnd=now)),listOf(shift));assertEquals(Duration.ofMinutes(30),r.overage);assertEquals(Duration.ZERO,r.shortfall) }
    @Test fun projectedShortfall(){ assertEquals(Duration.ofHours(32),result(listOf(active),listOf(shift)).shortfall) }
    @Test fun reachedAndExceededNeverNegative(){ val reached=result(listOf(session(minutes=2400)));assertEquals("TARGET REACHED",reached.status);val over=result(listOf(session(minutes=2418)));assertEquals("OVER TARGET",over.status);assertEquals(Duration.ZERO,over.remaining);assertTrue(over.progress>1) }
    @Test fun rolloverResetsWithoutWrites(){ val s=session();assertEquals(Duration.ZERO,result(listOf(s),time=Instant.parse("2026-10-10T00:00:00Z")).paid) }
    @Test fun overnightAttributedToClockInWeek(){ val s=WorkSession(id=1,clockIn=Instant.parse("2026-10-09T23:00:00Z"),clockOut=Instant.parse("2026-10-10T04:00:00Z"),state=ShiftState.COMPLETE);assertEquals(Duration.ZERO,result(listOf(s),time=s.clockOut!!).paid) }
    @Test fun exactlySamePaidAsWeeklyGross(){ val sessions=listOf(session(),active.copy(lunchStart=now.minusSeconds(900),lunchEnd=now.minusSeconds(300)));assertEquals(PayEstimator(ShiftEngine()).week(sessions,DEFAULT_PAY_RATES,now,zone).paid,result(sessions).paid) }
    @Test fun upcomingActualFortyHourSchedule(){ val shifts=(0..4).map { shift.copy(id=it.toLong(),date=LocalDate.of(2026,10,10).plusDays(it.toLong()),scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0)) };val r=result(schedule=shifts,time=Instant.parse("2026-10-10T00:00:00Z"));assertEquals(Duration.ofHours(40),r.projected) }
    @Test fun noProjectionForPreviousWeekActive(){ val old=active.copy(clockIn=Instant.parse("2026-10-02T23:00:00Z"));val r=result(listOf(old));assertNull(r.active);assertNull(r.projectedClockOut) }
}
