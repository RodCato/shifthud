package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.ui.schedule.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class ScheduleCompletionTest {
    private val date=LocalDate.of(2026,10,10)
    private val shift=ScheduledShift(1,date,LocalTime.of(8,0),LocalTime.of(17,0))
    private val start=Instant.parse("2026-10-10T08:00:00Z")
    private fun work(id:Long=1,link:Long?=1,end:Instant?=null)=WorkSession(id,link,start,clockOut=end,state=if(end==null) ShiftState.WORKING else ShiftState.COMPLETE)
    private fun status(vararg records:WorkSession)=scheduleCompletion(listOf(shift),records.toList()).states[1L]
    @Test fun beforeClockInIsScheduled(){assertEquals(ScheduleCompletionStatus.SCHEDULED,status())}
    @Test fun activeIsWorking(){assertEquals(ScheduleCompletionStatus.WORKING,status(work()))}
    @Test fun openLunchIsPaused(){assertEquals(ScheduleCompletionStatus.ON_LUNCH,status(work().copy(lunchStart=start.plusSeconds(3600),state=ShiftState.ON_LUNCH)))}
    @Test fun lunchEndedIsWorking(){assertEquals(ScheduleCompletionStatus.WORKING,status(work().copy(lunchStart=start.plusSeconds(3600),lunchEnd=start.plusSeconds(7200))))}
    @Test fun earlyAndLateClockOutComplete(){listOf(1L,9L,12L).forEach { assertEquals(ScheduleCompletionStatus.COMPLETED,status(work(end=start.plusSeconds(it*3600)))) }}
    @Test fun activeSegmentPreventsCompletionRegardlessOfOrder(){
        val ended=work(end=start.plusSeconds(3600));val active=work(2).copy(clockIn=start.plusSeconds(7200))
        assertEquals(ScheduleCompletionStatus.WORKING,status(ended,active));assertEquals(ScheduleCompletionStatus.WORKING,status(active,ended))
        assertEquals(ScheduleCompletionStatus.COMPLETED,status(ended,active.copy(clockOut=start.plusSeconds(10800),state=ShiftState.COMPLETE)))
    }
    @Test fun unscheduledAndOtherSameDateSessionsCannotCompleteShift(){assertEquals(ScheduleCompletionStatus.SCHEDULED,status(work(link=null,end=start.plusSeconds(3600)),work(link=2,end=start.plusSeconds(7200))))}
    @Test fun overnightAndCorrectionsKeepExplicitAssociation(){
        val overnight=shift.copy(scheduledStart=LocalTime.of(23,0),scheduledEnd=LocalTime.of(4,0))
        val corrected=work(end=start.plusSeconds(86400)).copy(clockIn=start.minusSeconds(86400),correctionRevision=1)
        assertEquals(ScheduleCompletionStatus.COMPLETED,scheduleCompletion(listOf(overnight),listOf(corrected)).states[1L])
    }
    @Test fun deletionAndUnlinkingRemoveCompletion(){
        val completed=work(end=start.plusSeconds(3600))
        assertEquals(1,scheduleCompletion(listOf(shift),listOf(completed)).completed)
        assertEquals(0,scheduleCompletion(listOf(shift),emptyList()).completed)
        assertEquals(0,scheduleCompletion(listOf(shift),listOf(completed.copy(scheduledShiftId=null))).completed)
    }
    @Test fun selectedWeekAndScheduleEditsControlSummary(){
        val schedules=listOf(shift,shift.copy(id=2,date=date.plusDays(6)),shift.copy(id=3,date=date.plusDays(7)))
        val records=listOf(work(end=start.plusSeconds(3600)),work(link=3,end=start.plusSeconds(3600)))
        val week=scheduleWeek(date)
        assertEquals("1 of 2 shifts completed",scheduleCompletion(week.scheduleRows(schedules),records).summary)
        assertEquals("1 of 1 shifts completed",scheduleCompletion(week.moveWeeks(1).scheduleRows(schedules),records).summary)
        assertEquals("0 of 1 shifts completed",scheduleCompletion(week.scheduleRows(schedules.drop(1)),records).summary)
        assertEquals("0 of 0 shifts completed",scheduleCompletion(emptyList(),records).summary)
    }
}
