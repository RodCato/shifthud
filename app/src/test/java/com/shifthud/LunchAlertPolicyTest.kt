package com.shifthud

import com.shifthud.notification.lunch.*
import com.shifthud.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LunchAlertPolicyTest {
    private val start=Instant.parse("2026-10-17T23:45:00Z")
    private val session=WorkSession(10,clockIn=start.minusSeconds(5*3600),lunchStart=start,state=ShiftState.ON_LUNCH)
    private val settings=LunchAlertSettings(planned=true)
    private val armed=LunchAlertLedger(lunchIdentity(session)!!)
    private fun at(minutes:Long)=start.plusSeconds(minutes*60)
    private fun decision(minutes:Long,state:LunchAlertLedger?=armed,plan:Int=48,config:LunchAlertSettings=settings)=lunchAlertDecision(session,config,plan,state,at(minutes))
    @Test fun neverBefore31ActualMinutes(){assertNull(lunchAlertDecision(session,settings,48,armed,at(31).minusNanos(1)).event);assertEquals(LunchAlertKind.MINIMUM,decision(31).event!!.kind)}
    @Test fun planned48FollowsMinimum(){val d=decision(31);val s=acknowledgeLunchAlert(d.ledger!!,d.event!!,settings,48,at(31));assertNull(decision(47,s).event);assertEquals(LunchAlertKind.PLANNED,decision(48,s).event!!.kind)}
    @Test fun equal31Combines(){val d=decision(31,plan=31);val s=acknowledgeLunchAlert(d.ledger!!,d.event!!,settings,31,at(31));assertNull(decision(31,s,31).event);assertNull(decision(32,s,31).next)}
    @Test fun planned31WorksWhenMinimumDisabled(){val d=decision(31,plan=31,config=settings.copy(minimum=false));assertEquals(LunchAlertKind.PLANNED,d.event!!.kind)}
    @Test fun snoozeChoicesAndCustomAreRelativeToActionTime(){val posted=armed.copy(receipt="receipt");for(n in listOf(5,10,15,20,30,1,17,120)){val next=snoozeLunchAlert(session,posted,"receipt",n,at(33))!!;assertEquals(at(33+n.toLong()),next.snoozeAt);assertEquals(start,session.lunchStart)}}
    @Test fun invalidSnoozesRejected(){for(n in listOf(0,-1,121))assertThrows(IllegalArgumentException::class.java){snoozeLunchAlert(session,armed,"r",n,at(31))}}
    @Test fun actionCanOnlyCreateOneChain(){val s=snoozeLunchAlert(session,armed.copy(receipt="r"),"r",10,at(32))!!;assertNull(snoozeLunchAlert(session,s,"r",10,at(33)))}
    @Test fun repeatedSnoozeReplacesNextChain(){val s=armed.copy(minimumDone=true,plannedDone=true,snoozeAt=at(41));val d=decision(41,s);val next=acknowledgeLunchAlert(d.ledger!!,d.event!!,settings.copy(repeat=true),48,at(41));assertEquals(at(51),next.snoozeAt);assertEquals(at(53),snoozeLunchAlert(session,next,next.receipt,10,at(43))!!.snoozeAt)}
    @Test fun noAutomaticRepeatWhenDisabled(){val s=armed.copy(minimumDone=true,plannedDone=true,snoozeAt=at(41));val d=decision(41,s);assertNull(acknowledgeLunchAlert(d.ledger!!,d.event!!,settings,48,at(41)).snoozeAt)}
    @Test fun endLunchAndClockOutCancel(){for(s in listOf(session.copy(lunchEnd=at(30),state=ShiftState.WORKING),session.copy(lunchEnd=at(30),clockOut=at(30),state=ShiftState.COMPLETE))){assertTrue(lunchAlertDecision(s,settings,48,armed,at(50)).cancel);assertNull(snoozeLunchAlert(s,armed.copy(receipt="r"),"r",10,at(50)))}}
    @Test fun duplicatesSuppressedAcrossRecovery(){val d=decision(31);val saved=acknowledgeLunchAlert(d.ledger!!,d.event!!,settings,48,at(31));assertNull(decision(32,saved).event)}
    @Test fun oldOrUnarmedHistoryNeverReplays(){assertNull(decision(65).event);assertNull(decision(49,null).event);assertNull(decision(24*60+1).event)}
    @Test fun weeklyPlanChangesPendingTarget(){val d=decision(31);val s=acknowledgeLunchAlert(d.ledger!!,d.event!!,settings,48,at(31));assertEquals(at(60),decision(40,s,60).next);assertEquals(LunchAlertKind.PLANNED,decision(40,s,35).event!!.kind)}
    @Test fun changedPlanTo31DoesNotDuplicateDeliveredMinimum(){val d=decision(31);val s=acknowledgeLunchAlert(d.ledger!!,d.event!!,settings,48,at(31));assertNull(decision(35,s,31).event)}
    @Test fun overnightUsesElapsedTimestamp(){assertEquals(Instant.parse("2026-10-18T00:16:00Z"),decision(0).next)}
    @Test fun correctionsInvalidateStaleActions(){assertNull(snoozeLunchAlert(session.copy(correctionRevision=1),armed.copy(receipt="r"),"r",10,at(31)))}
    @Test fun meaningfulGarminTextAndNoTimekeepingActions(){assertEquals("Your 31-minute lunch minimum is complete.",lunchAlertText(LunchAlertKind.MINIMUM,31).second);assertTrue(lunchAlertText(LunchAlertKind.PLANNED,48).second.contains("48-minute"));assertTrue(lunchAlertText(LunchAlertKind.SNOOZE,48).second.contains("still running"))}
}
