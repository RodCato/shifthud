package com.shifthud

import com.shifthud.notification.*
import com.shifthud.data.repository.ShiftSnapshot
import com.shifthud.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ShiftEndReminderTest {
    private val zone = ZoneOffset.UTC
    private val date = LocalDate.of(2026,10,7)
    private val schedule = ScheduledShift(8,date,LocalTime.of(4,0),LocalTime.of(13,0))
    private val start = date.atTime(4,0).toInstant(zone)
    private val end = date.atTime(13,0).toInstant(zone)
    private val target = end.minusSeconds(900)
    private val session = WorkSession(7,8,start)
    private val snapshot = ShiftSnapshot(listOf(schedule),session)
    private val settings = ShiftEndSettings()
    private fun evaluate(now: Instant = start, previous: ShiftEndDelivery? = null, snap: ShiftSnapshot = snapshot, config: ShiftEndSettings = settings) = evaluateShiftEnd(snap,config,previous,now,zone)
    @Test fun serviceWakesAtUpcomingTarget(){ assertEquals(2_000L,com.shifthud.service.shiftEndRefreshDelayMillis(target,target.minusSeconds(2),60_000)) }
    @Test fun serviceKeepsEarlierMinuteWake(){ assertEquals(30_000L,com.shifthud.service.shiftEndRefreshDelayMillis(target,start,30_000)) }
    @Test fun overdueBlockedPostDoesNotBusyLoop(){ assertEquals(60_000L,com.shifthud.service.shiftEndRefreshDelayMillis(target,target.plusSeconds(3),60_000)) }
    @Test fun noTargetKeepsNormalCadence(){ assertEquals(60_000L,com.shifthud.service.shiftEndRefreshDelayMillis(null,start,60_000)) }
    @Test fun defaults(){ assertEquals(ShiftEndSettings(true,15,10),settings) }
    @Test fun allLeads(){ SHIFT_END_LEADS.forEach { assertEquals(end.minusSeconds(it*60L),evaluate(config=settings.copy(leadMinutes=it)).state!!.target) } }
    @Test fun earlyClockIn(){ assertEquals(target,evaluate(snap=snapshot.copy(session=session.copy(clockIn=start.minusSeconds(300)))).state!!.target) }
    @Test fun lateClockIn(){ assertEquals(target,evaluate(snap=snapshot.copy(session=session.copy(clockIn=start.plusSeconds(900)))).state!!.target) }
    @Test fun unscheduled(){ assertNull(evaluate(snap=snapshot.copy(session=session.copy(scheduledShiftId=null))).state) }
    @Test fun noGuessFromUnlinkedSchedule(){ assertNull(evaluate(snap=snapshot.copy(session=session.copy(scheduledShiftId=99))).state) }
    @Test fun upcomingWaits(){ assertFalse(evaluate(target.minusSeconds(2)).due) }
    @Test fun delayedCrossing(){ assertTrue(evaluate(target.plusSeconds(3),evaluate().state).due) }
    @Test fun postedDoesNotRepeat(){ val posted=evaluate(target).state!!.copy(posted=true); repeat(30){assertFalse(evaluate(target.plusSeconds(it*60L),posted).due)} }
    @Test fun blockedPostRemainsDue(){ val pending=evaluate(target).state;assertTrue(evaluate(target.plusSeconds(60),pending).due) }
    @Test fun workingTarget(){ assertEquals(target,evaluate().state!!.target) }
    @Test fun onLunchStillDue(){ val lunch=session.copy(lunchStart=target.minusSeconds(60),state=ShiftState.ON_LUNCH);assertTrue(evaluate(target,snap=snapshot.copy(session=lunch)).due) }
    @Test fun completedLunchStillDue(){ val lunch=session.copy(lunchStart=start.plusSeconds(3600),lunchEnd=start.plusSeconds(7200));assertTrue(evaluate(target,snap=snapshot.copy(session=lunch)).due) }
    @Test fun snoozeUsesWallClock(){ val p=evaluate(target).state!!.copy(posted=true);val tapped=target.plusSeconds(123);val next=snoozeShiftEnd(snapshot,settings,p,p.receipt,tapped,zone)!!;assertEquals(tapped.plusSeconds(600),next.target);assertFalse(next.posted) }
    @Test fun repeatedSnoozeReplacesAndRejectsOldReceipt(){ val p=evaluate(target).state!!.copy(posted=true);val first=snoozeShiftEnd(snapshot,settings,p,p.receipt,target,zone)!!.copy(posted=true);val next=snoozeShiftEnd(snapshot,settings,first,first.receipt,first.target,zone)!!;assertEquals(end.plusSeconds(300),next.target);assertEquals(2L,next.generation);assertNull(snoozeShiftEnd(snapshot,settings,next,p.receipt,end,zone)) }
    @Test fun postEndWording(){ val p=evaluate(target).state!!;assertEquals("Scheduled shift ended",shiftEndTitle(p,end));assertEquals("Shift ending soon",shiftEndTitle(p.copy(generation=1),target));assertEquals("Shift ends in 15 minutes",shiftEndTitle(p,target)) }
    @Test fun clockOutClearsPending(){ val completed=snapshot.copy(session=session.copy(clockOut=end,state=ShiftState.COMPLETE));val d=evaluate(end,evaluate().state,completed);assertNull(d.state);assertTrue(d.cancel) }
    @Test fun clockOutClearsSnooze(){ val p=evaluate().state!!.copy(generation=1,target=end.plusSeconds(600));assertNull(evaluate(end,p,snapshot.copy(session=session.copy(clockOut=end,state=ShiftState.COMPLETE))).state) }
    @Test fun futureEditReplacesTarget(){ val p=evaluate(target).state!!.copy(posted=true);val changed=snapshot.copy(schedule=listOf(schedule.copy(scheduledEnd=LocalTime.of(14,0))));val d=evaluate(target,p,changed);assertEquals(target.plusSeconds(3600),d.state!!.target);assertFalse(d.due);assertTrue(d.cancel);assertFalse(d.state!!.posted) }
    @Test fun pastEditIsNotReplayed(){ val changed=snapshot.copy(schedule=listOf(schedule.copy(scheduledEnd=LocalTime.of(12,50))));val d=evaluate(target,evaluate().state,changed);assertFalse(d.due);assertTrue(d.state!!.skipped) }
    @Test fun deletionClears(){ val d=evaluate(target,evaluate().state,snapshot.copy(schedule=emptyList()));assertNull(d.state);assertTrue(d.cancel) }
    @Test fun staleSessionCannotSnooze(){ val p=evaluate(target).state!!.copy(posted=true);assertNull(snoozeShiftEnd(snapshot.copy(session=session.copy(id=9)),settings,p,p.receipt,target,zone)) }
    @Test fun staleScheduleCannotSnooze(){ val p=evaluate(target).state!!.copy(posted=true);assertNull(snoozeShiftEnd(snapshot.copy(schedule=emptyList()),settings,p,p.receipt,target,zone)) }
    @Test fun recoveryPreservesUpcoming(){ val pending=evaluate().state;assertEquals(pending,evaluate(target.minusSeconds(1),pending).state) }
    @Test fun recoveryRecentCrossing(){ assertTrue(evaluate(target.plusSeconds(180),evaluate().state).due) }
    @Test fun recoveryDoesNotReplayAncientEvent(){ val d=evaluate(end.plusSeconds(3600),evaluate().state);assertFalse(d.due);assertTrue(d.state!!.skipped) }
    @Test fun disabledClears(){ assertNull(evaluate(target,evaluate().state,config=settings.copy(enabled=false)).state) }
    @Test fun overnightUsesNextDay(){ val overnight=schedule.copy(scheduledStart=LocalTime.of(23,0),scheduledEnd=LocalTime.of(4,0));assertEquals(date.plusDays(1).atTime(3,45).toInstant(zone),evaluate(snap=snapshot.copy(schedule=listOf(overnight))).state!!.target) }
    @Test fun leadEditBaselinesPastBoundary(){ val d=evaluate(target,evaluate().state,config=settings.copy(leadMinutes=30));assertTrue(d.state!!.skipped);assertFalse(d.due) }
    @Test fun snoozeSettingDoesNotMoveExistingTarget(){ val p=evaluate().state!!.copy(target=end,generation=1);assertEquals(p,evaluate(target,p,config=settings.copy(snoozeMinutes=5)).state) }
}
