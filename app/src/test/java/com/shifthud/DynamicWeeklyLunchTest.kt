package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import com.shifthud.domain.scheduleimport.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DynamicWeeklyLunchTest {
    private val saturday=LocalDate.of(2026,10,17)
    private val zone=ZoneOffset.UTC
    private fun at(day:LocalDate,h:Int,m:Int=0)=day.atTime(h,m).toInstant(zone)
    private val shifts=(0..4).map { day -> ScheduledShift(day+1L,saturday.plusDays(day.toLong()),
        LocalTime.of(if(day==0)16 else if(day==1)10 else 8,0),LocalTime.of(if(day==0)23 else if(day==1)18 else 17,0),60,
        employerNetMinutes=if(day==0)360 else if(day==1)420 else 480,employerLunchMinutes=60) }
    private fun plan(minutes:Int,records:List<WorkSession> = emptyList(),now:Instant=at(saturday,0),schedule:List<ScheduledShift> = shifts,target:Duration=Duration.ofHours(40)) =
        clockOutPlan(records,schedule,target,PlannerSettings().withWeek(saturday,minutes),now,zone)
    @Test fun passportAcceptanceDurationsAndShortfalls() {
        mapOf(60 to 2220L,45 to 2295L,35 to 2345L,31 to 2365L).forEach { (lunch,paid) ->
            val p=plan(lunch)
            assertEquals(Duration.ofMinutes(paid),p.projected)
            assertEquals(Duration.ofMinutes(2400-paid),p.shortfall)
            assertNull(p.clockOut) // No unauthorized extension to fill the shortfall.
        }
        assertEquals(2220,shifts.sumOf { it.employerNetMinutes!! })
    }
    @Test fun completedActualLunchReplacesPlanWithoutDoubleCounting() {
        val s=shifts.first()
        val actual=WorkSession(1,s.id,at(s.date,16),at(s.date,19),at(s.date,19,20),at(s.date,23),ShiftState.COMPLETE)
        val p=plan(35,listOf(actual),at(saturday.plusDays(1),0))
        assertEquals(Duration.ofMinutes(400),p.completed)
        assertEquals(Duration.ofMinutes(2360),p.projected)
        assertEquals(Duration.ofMinutes(400),plan(60,listOf(actual),at(saturday.plusDays(1),0)).completed)
    }
    @Test fun actualLunchPausesAndResumesRegardlessOfSelectedWeeklyPlan() {
        val s=shifts.first()
        val lunch=WorkSession(1,s.id,at(s.date,16),at(s.date,19),state=ShiftState.ON_LUNCH,autoLunchMinutes=60)
        val a=plan(31,listOf(lunch),at(s.date,19,10));val b=plan(31,listOf(lunch),at(s.date,19,40))
        assertEquals(a.worked,b.worked);assertEquals(a.projected,b.projected)
        val resumed=lunch.copy(lunchEnd=at(s.date,20),state=ShiftState.WORKING)
        assertEquals(b.worked.plusMinutes(1),plan(31,listOf(resumed),at(s.date,20,1)).worked)
        assertEquals(a.projected,plan(31,listOf(resumed),at(s.date,20,1)).projected)
    }
    @Test fun earlyLatePunchesAndFridayRecommendation() {
        val monday=saturday.plusDays(2)
        val week=(0..4).map { ScheduledShift(it+1L,monday.plusDays(it.toLong()),LocalTime.of(4,0),LocalTime.of(13,0),60) }
        val completed=week.take(4).map { WorkSession(it.id,it.id,at(it.date,4),at(it.date,8),at(it.date,8,45),at(it.date,13),ShiftState.COMPLETE) }
        val friday=week.last().date
        fun live(hour:Int)=plan(45,completed+WorkSession(5,5,at(friday,hour)),at(friday,6),week)
        assertEquals(at(friday,11,45),live(4).clockOut)
        assertEquals(at(friday,12,45),live(5).clockOut)
        assertEquals(at(friday,10,45),live(3).clockOut)
        assertEquals(Duration.ofHours(40),plan(60,schedule=week).projected)
    }
    @Test fun overnightUsesStartWeekAndNextDayEnd() {
        val friday=saturday.plusDays(6)
        val s=ScheduledShift(1,friday,LocalTime.of(22,0),LocalTime.of(6,0))
        val p=plan(45,now=at(friday,20),schedule=listOf(s),target=Duration.ofMinutes(435))
        assertEquals(at(friday.plusDays(1),6),p.clockOut)
    }
    @Test fun weekIndependenceAndLegacyOverridesRemainStored() {
        val settings=PlannerSettings(plannedMinutes=50).withWeek(saturday,31).withWeek(saturday.plusWeeks(1),45)
        assertEquals(31,settings.weeklyMinutes(saturday.plusDays(6)))
        assertEquals(45,settings.weeklyMinutes(saturday.plusDays(7)))
        assertEquals(50,settings.weeklyMinutes(saturday.plusDays(14)))
        val legacy=shifts.first().copy(plannedLunchMinutes=90,lunchPlanApplied=true)
        assertEquals(Duration.ofMinutes(31),settings.lunch(legacy,null))
        assertEquals(90,legacy.plannedLunchMinutes)
    }
    @Test fun validationEnforcesWeeklyMinimumAndEmployerPolicy() {
        listOf(0,30,241).forEach { assertTrue(runCatching { PlannerSettings().withWeek(saturday,it) }.isFailure) }
        assertTrue(runCatching { PlannerSettings(minimumMinutes=45).withWeek(saturday,35) }.isFailure)
        assertEquals(240,PlannerSettings().withWeek(saturday,240).weeklyMinutes(saturday))
    }
    @Test fun previousWeekUsesActualRecordsAndNeverProjectsMissedSchedules() {
        val actual=WorkSession(1,1,at(saturday,16),clockOut=at(saturday,23),state=ShiftState.COMPLETE)
        val p=clockOutPlan(listOf(actual),shifts,Duration.ofHours(40),PlannerSettings().withWeek(saturday,35),
            at(saturday.plusWeeks(1),12),zone,saturday)
        assertEquals(Duration.ofHours(7),p.worked);assertEquals(Duration.ZERO,p.futurePaid)
    }
    @Test fun higherPolicyMinimumDoesNotDeleteLegacyCustomSettings() {
        val legacy=PlannerSettings(custom=mapOf(saturday to 31)).copy(minimumMinutes=45)
        assertEquals(31,legacy.custom[saturday]);assertEquals(60,legacy.weeklyMinutes(saturday))
        assertEquals(45,legacy.copy(weeklyLunches=mapOf(saturday to 35)).weeklyMinutes(saturday))
    }
    @Test fun importerSeparatesEmployerEvidenceFromPersonalPlan() {
        val c=extractSchedule("10/17/2026\nSat Oct 17 4 PM–11 PM Net 6 hours").rows.single()
        val shift=c.shift()!!
        assertNull(shift.plannedLunchMinutes)
        assertEquals(60,shift.employerLunchMinutes);assertEquals(360,shift.employerNetMinutes)
        assertEquals(Duration.ofMinutes(385),plan(35,schedule=listOf(shift.copy(id=1))).projected)
        val noNet=extractSchedule("10/17/2026\nSat Oct 17 4 PM–11 PM").rows.single()
        assertEquals(Confidence.HIGH,noNet.confidence);assertNotNull(noNet.shift())
        assertNull(noNet.shift()!!.employerNetMinutes)
    }
}
