package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WorkWeekTest {
    private val zone=ZoneId.of("America/Chicago")
    private val estimator=PayEstimator(ShiftEngine())
    private val expected=WorkWeek(LocalDate.of(2026,10,3),LocalDate.of(2026,10,9))
    private fun instant(value:String)=LocalDateTime.parse(value).atZone(zone).toInstant()
    private fun session(start:String,end:String)=WorkSession(clockIn=instant(start),clockOut=instant(end),state=ShiftState.COMPLETE)
    private fun total(sessions:List<WorkSession>,now:String="2026-10-06T18:00")=estimator.week(sessions,DEFAULT_PAY_RATES,instant(now),zone)
    @Test fun saturdayStartsWeek(){assertEquals(expected,workWeekFor(LocalDate.of(2026,10,3)))}
    @Test fun sundayStaysInWeek(){assertEquals(expected,workWeekFor(LocalDate.of(2026,10,4)))}
    @Test fun mondayStaysInWeek(){assertEquals(expected,workWeekFor(LocalDate.of(2026,10,5)))}
    @Test fun tuesdayStaysInWeek(){assertEquals(expected,workWeekFor(LocalDate.of(2026,10,6)))}
    @Test fun wednesdayStaysInWeek(){assertEquals(expected,workWeekFor(LocalDate.of(2026,10,7)))}
    @Test fun thursdayStaysInWeek(){assertEquals(expected,workWeekFor(LocalDate.of(2026,10,8)))}
    @Test fun fridayEndsWeek(){assertEquals(expected,workWeekFor(LocalDate.of(2026,10,9)))}
    @Test fun nextSaturdayStartsNewWeek(){assertEquals(WorkWeek(LocalDate.of(2026,10,10),LocalDate.of(2026,10,16)),workWeekFor(LocalDate.of(2026,10,10)))}
    @Test fun suppliedSaturdayPunchesContributeEightHoursSixMinutesAnd12960OnTuesday(){
        val saturday=session("2026-10-03T04:00","2026-10-03T13:00").copy(lunchStart=instant("2026-10-03T09:47"),lunchEnd=instant("2026-10-03T10:41"),manuallyEntered=true)
        assertEquals(PayEstimate(Duration.ofMinutes(486),12960),total(listOf(saturday)))
        assertEquals(total(listOf(saturday)),total(listOf(saturday.copy(manuallyEntered=false))))
    }
    @Test fun paidAndGrossIncludeFridayAndExcludePreviousFridayAndNextSaturday(){
        val sessions=listOf(session("2026-10-02T04:00","2026-10-02T05:00"),session("2026-10-03T04:00","2026-10-03T06:00"),
            session("2026-10-09T04:00","2026-10-09T07:00"),session("2026-10-10T04:00","2026-10-10T08:00"))
        assertEquals(PayEstimate(Duration.ofHours(5),8000),total(sessions,"2026-10-09T18:00"))
    }
    @Test fun previousFridayOvernightExcludedEvenThoughEndsInCurrentWeek(){
        assertEquals(PayEstimate(Duration.ZERO,0),total(listOf(session("2026-10-02T23:00","2026-10-03T07:00"))))
    }
    @Test fun fridayOvernightAssignedEntirelyToFridayWeek(){
        val s=session("2026-10-09T23:00","2026-10-10T07:00")
        assertEquals(expected,workWeekFor(s.clockIn.atZone(zone).toLocalDate()))
        assertEquals(PayEstimate(Duration.ofMinutes(30),800),total(listOf(s),"2026-10-09T23:30"))
        assertEquals(PayEstimate(Duration.ZERO,0),total(listOf(s),"2026-10-10T08:00"))
    }
    @Test fun saturdayOvernightBelongsToNewWeek(){
        val s=session("2026-10-03T23:00","2026-10-04T07:00")
        assertEquals(PayEstimate(Duration.ofHours(8),12800),total(listOf(s)))
    }
    @Test fun localFridayUtcSaturdayUsesFridayWeek(){
        val s=session("2026-10-02T23:00","2026-10-03T01:00")
        assertEquals(LocalDate.of(2026,10,3),s.clockIn.atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(PayEstimate(Duration.ZERO,0),total(listOf(s)))
    }
    @Test fun timezoneIsNotHardcodedToChicago(){
        val tokyo=ZoneId.of("Asia/Tokyo")
        val s=WorkSession(clockIn=Instant.parse("2026-10-02T16:00:00Z"),clockOut=Instant.parse("2026-10-02T17:00:00Z"),state=ShiftState.COMPLETE)
        assertEquals(PayEstimate(Duration.ofHours(1),1600),estimator.week(listOf(s),DEFAULT_PAY_RATES,instant("2026-10-06T18:00"),tokyo))
    }
    @Test fun activeFridayOvernightDoesNotLeakIntoNewWeek(){
        val s=WorkSession(clockIn=instant("2026-10-09T23:00"))
        assertEquals(PayEstimate(Duration.ZERO,0),total(listOf(s),"2026-10-10T01:00"))
    }
    @Test fun yearBoundaryUsesPreviousSaturday(){
        assertEquals(WorkWeek(LocalDate.of(2026,12,26),LocalDate.of(2027,1,1)),workWeekFor(LocalDate.of(2027,1,1)))
    }
    @Test fun dashboardWeekLabelMatchesPolicy(){assertEquals("THIS WEEK · Saturday–Friday",WORK_WEEK_LABEL)}
}
