package com.shifthud

import com.shifthud.domain.weekly.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.notification.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeeklyWarningsTest {
    private val now=Instant.parse("2026-10-09T12:00:00Z")
    private val settings=WeeklyTargetSettings()
    private fun progress(minutes:Long,active:Boolean=true,key:String="1:0",date:LocalDate=LocalDate.of(2026,10,9)) = WeeklyTargetProgress(workWeekFor(date),Duration.ofMinutes(minutes),settings.target,settings.target,null,false,if(active)WorkSession(id=1,clockIn=now.minusSeconds(3600)) else null,key)
    private fun evaluate(minutes:Long,previous:WeeklyWarningLedger?=null,config:WeeklyTargetSettings=settings,active:Boolean=true,key:String="1:0")=evaluateWeeklyWarning(progress(minutes,active,key).copy(target=config.target),config,previous,now)
    private fun baseline()=evaluate(2200).ledger
    @Test fun noRetroactiveOnFirstObservation(){ assertNull(evaluate(2390).boundary);assertEquals(setOf(120,30,10),evaluate(2390).ledger.consumed) }
    @Test fun twoHourWarning(){assertEquals(120,evaluate(2280,baseline()).boundary)}
    @Test fun thirtyMinuteWarning(){assertEquals(30,evaluate(2370,baseline()).boundary)}
    @Test fun tenMinuteWarning(){assertEquals(10,evaluate(2390,baseline()).boundary)}
    @Test fun reachedWarning(){assertEquals(0,evaluate(2400,baseline()).boundary)}
    @Test fun repeatedTicksDeduplicate(){val p=evaluate(2280,baseline()).acknowledged().ledger;repeat(10){assertNull(evaluate(2280L+it,p).boundary)}}
    @Test fun delayedCrossingUsesMostRelevantOnly(){val d=evaluate(2395,baseline());assertEquals(10,d.boundary);assertEquals(setOf(120,30),d.ledger.consumed);assertNull(evaluate(2395,d.acknowledged().ledger).boundary)}
    @Test fun blockedPostingDoesNotConsumeCandidate(){val d=evaluate(2390,baseline());assertEquals(10,evaluate(2391,d.ledger).boundary)}
    @Test fun correctionBackDoesNotReplay(){val sent=evaluate(2390,baseline()).acknowledged().ledger;val back=evaluate(2200,sent,key="1:1").ledger;assertNull(evaluate(2390,back,key="1:1").boundary)}
    @Test fun correctionForwardSkipsHistorical(){assertNull(evaluate(2400,baseline(),key="1:1").boundary)}
    @Test fun deletedSessionChangesBaseline(){assertNull(evaluate(2390,baseline(),key="").boundary)}
    @Test fun targetChangeNoBurst(){val d=evaluate(2200,baseline(),config=settings.copy(targetMinutes=2100));assertNull(d.boundary);assertTrue(0 in d.ledger.consumed)}
    @Test fun newlyEnabledWarningsNoReplay(){val disabled=evaluate(2200,config=settings.copy(warnings=emptySet())).ledger;assertNull(evaluate(2390,disabled).boundary)}
    @Test fun completedWeekDoesNotNotify(){val d=evaluate(2353,baseline(),active=false);assertNull(d.boundary);assertTrue(d.cancel);assertNull(d.nextTarget)}
    @Test fun nextSaturdayNewKey(){val d=evaluateWeeklyWarning(progress(0,date=LocalDate.of(2026,10,10)),settings,baseline(),now);assertTrue(d.ledger.consumed.isEmpty());assertEquals(LocalDate.of(2026,10,10),d.ledger.weekStart)}
    @Test fun recoverySameLedger(){val sent=evaluate(2370,baseline()).acknowledged().ledger;assertNull(evaluate(2380,sent.copy()).boundary)}
    @Test fun disabledTrackingCancels(){assertTrue(evaluate(2280,baseline(),settings.copy(enabled=false)).cancel)}
    @Test fun activeWakeUsesRemainingPaidTime(){assertEquals(now.plusSeconds(80*60),evaluate(2200,baseline()).nextTarget)}
    @Test fun lunchHasNoClockBasedWake(){val p=progress(2200);val lunch=p.copy(active=p.active!!.copy(lunchStart=now,state=ShiftState.ON_LUNCH));assertNull(evaluateWeeklyWarning(lunch,settings,baseline(),now).nextTarget)}
    @Test fun conciseWatchText(){val d=evaluate(2370,baseline());val text=weeklyWarningText(d,progress(2370));assertEquals("Weekly target in 30 minutes",text.first);assertEquals("39h 30m worked · 30m remaining",text.second);assertTrue(text.second.length<80)}
    @Test fun reachedText(){val d=evaluate(2400,baseline());assertEquals("Weekly target reached" to "40h 0m paid this week",weeklyWarningText(d,progress(2400)))}
    @Test fun keyContainsWeekTargetBoundary(){assertEquals("2026-10-03:2400:30",baseline().receipt(30))}
}
