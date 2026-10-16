package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.planner.PlannerSettings
import com.shifthud.ui.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class ScheduleActualsTest {
    private val date=LocalDate.of(2026,10,11)
    private val shift=ScheduledShift(1,date,LocalTime.of(7,0),LocalTime.of(16,0),plannedLunchMinutes=99)
    private fun instant(time:String)=Instant.parse("2026-10-11T${time}:00Z")
    private val completed=WorkSession(1,1,instant("07:00"),instant("12:36"),instant("13:33"),instant("16:00"),ShiftState.COMPLETE)
    private fun display(records:List<WorkSession>,plan:Int=56,now:Instant=instant("17:00"),s:ScheduledShift=shift,h24:Boolean=false)=
        scheduleActuals(s,records,plan,now,ZoneOffset.UTC,Locale.US,h24)
    @Test fun completedUsesActualLunchAndPaidInsteadOfEitherPlan() {
        val result=display(listOf(completed))
        assertEquals("Completed · 8h 3m paid",result.summary)
        assertEquals("Lunch: 12:36 PM – 1:33 PM (57m)",result.lunch)
        assertEquals(result,display(listOf(completed),plan=31))
    }
    @Test fun missingLunchNeverUsesPlannedDuration() {
        val result=display(listOf(completed.copy(lunchStart=null,lunchEnd=null)))
        assertEquals("No lunch recorded",result.lunch)
        assertEquals("Completed · 9h 0m paid",result.summary)
    }
    @Test fun upcomingUsesWeeklyPreferenceNotLegacyOrUnrelatedPunches() {
        val settings=PlannerSettings(plannedMinutes=60,weeklyLunches=mapOf(LocalDate.of(2026,10,10) to 56))
        assertEquals("Scheduled · Planned lunch: 56m",display(listOf(completed.copy(scheduledShiftId=null)),settings.weeklyMinutes(date)).summary)
        assertNull(display(emptyList()).lunch)
        val next=shift.copy(date=LocalDate.of(2026,10,17))
        assertEquals("Scheduled · Planned lunch: 60m",display(emptyList(),settings.weeklyMinutes(next.date),s=next).summary)
    }
    @Test fun workingBeforeLunchAndActiveLunchRemainActual() {
        val active=completed.copy(lunchStart=null,lunchEnd=null,clockOut=null,state=ShiftState.WORKING)
        assertEquals("Working · 5h 0m paid",display(listOf(active),now=instant("12:00")).summary)
        assertEquals("No lunch recorded",display(listOf(active)).lunch)
        val lunch=active.copy(lunchStart=instant("12:36"),state=ShiftState.ON_LUNCH)
        val result=display(listOf(lunch),now=instant("13:00"))
        assertEquals("On lunch · 5h 36m paid",result.summary)
        assertEquals("Lunch in progress · Started 12:36 PM · 24m elapsed",result.lunch)
        assertTrue(result.intervals.isEmpty())
    }
    @Test fun multipleLinkedSessionsExposeIndividualIntervalsAndTotal() {
        val first=completed.copy(clockOut=instant("14:00"))
        val second=completed.copy(id=2,clockIn=instant("14:00"),lunchStart=instant("15:00"),lunchEnd=instant("15:15"),clockOut=instant("17:00"))
        val result=display(listOf(second,first))
        assertEquals("Completed · 8h 48m paid",result.summary)
        assertEquals("Lunch: 1h 12m total · 2 intervals · Expand for times",result.lunch)
        assertEquals(listOf("12:36 PM – 1:33 PM (57m)","3:00 PM – 3:15 PM (15m)"),result.intervals)
    }
    @Test fun overnightLunchShowsDateWhenCrossingWorkweekBoundary() {
        val friday=LocalDate.of(2026,10,16)
        val overnight=shift.copy(date=friday,scheduledStart=LocalTime.of(23,0),scheduledEnd=LocalTime.of(7,0))
        val record=completed.copy(clockIn=Instant.parse("2026-10-16T23:00:00Z"),lunchStart=Instant.parse("2026-10-17T02:00:00Z"),lunchEnd=Instant.parse("2026-10-17T02:30:00Z"),clockOut=Instant.parse("2026-10-17T07:00:00Z"))
        val result=display(listOf(record),s=overnight,h24=true)
        assertEquals("Completed · 7h 30m paid",result.summary)
        assertEquals("Lunch: Oct 17 02:00 – Oct 17 02:30 (30m)",result.lunch)
        assertEquals(LocalDate.of(2026,10,10),scheduleWeek(friday).start)
    }
    @Test fun correctionsDeletionAndReconstructionStayDerived() {
        val original=display(listOf(completed))
        assertEquals(original,display(listOf(completed.copy())))
        assertEquals("Completed · 8h 0m paid",display(listOf(completed.copy(lunchEnd=instant("13:36")))).summary)
        assertEquals("Scheduled · Planned lunch: 56m",display(emptyList()).summary)
        assertEquals("Scheduled · Planned lunch: 56m",display(listOf(completed),s=shift.copy(id=2)).summary)
    }
    @Test fun earlyLateClockOutAndIncompleteClockOutRemainTruthful() {
        assertEquals("Completed · 7h 3m paid",display(listOf(completed.copy(clockOut=instant("15:00")))).summary)
        assertEquals("Completed · 9h 3m paid",display(listOf(completed.copy(clockOut=instant("17:00")))).summary)
        assertEquals("Working · 8h 3m paid",display(listOf(completed.copy(clockOut=null,state=ShiftState.WORKING)),now=instant("16:00")).summary)
    }
}
