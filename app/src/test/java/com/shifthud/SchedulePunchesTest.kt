package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.ui.schedule.*
import com.shifthud.ui.time.decimalTime
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class SchedulePunchesTest {
    private val day=LocalDate.of(2026,10,12)
    private val shift=ScheduledShift(1,day,LocalTime.of(4,2),LocalTime.of(13,0))
    private fun at(time:String)=day.atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC)
    private val record=WorkSession(1,1,at("04:00"),at("09:50"),at("10:47"),at("13:00"),ShiftState.COMPLETE)
    private fun punches(s:ScheduledShift=shift,records:List<WorkSession>?=listOf(record),h24:Boolean=false)=schedulePunches(s,records,ZoneOffset.UTC,Locale.US,h24)
    @Test fun discrepancyShowsBothSourcesAndExactMatchOmitsDuplicate() {
        assertEquals("CLOCKED: 4:00 AM – 1:00 PM",punches().primary)
        assertEquals("Scheduled: 4:02 AM – 1:00 PM",punches().scheduled)
        assertNull(punches(shift.copy(scheduledStart=LocalTime.of(4,0))).scheduled)
        val late=record.copy(clockOut=at("13:15"))
        assertEquals("CLOCKED: 4:00 AM – 1:15 PM",punches(records=listOf(late)).primary)
    }
    @Test fun scheduleEditsNeverChangeActualPaidLunchOrDecimal() {
        val edited=shift.copy(scheduledStart=LocalTime.of(5,0),scheduledEnd=LocalTime.of(14,0))
        assertEquals(punches().primary,punches(edited).primary)
        val before=scheduleActuals(shift,listOf(record),35,at("15:00"),ZoneOffset.UTC,Locale.US,false)
        val after=scheduleActuals(edited,listOf(record),35,at("15:00"),ZoneOffset.UTC,Locale.US,false)
        assertEquals(before,after)
        assertEquals("8.05",decimalTime(before.paid!!,Locale.US))
        assertEquals("Lunch: 9:50 AM – 10:47 AM (57m)",before.lunch)
    }
    @Test fun correctionsUpdateClockedDisplayWithoutChangingSchedule() {
        val corrected=record.copy(clockIn=at("04:01"),clockOut=at("12:59"))
        assertEquals("CLOCKED: 4:01 AM – 12:59 PM",punches(records=listOf(corrected)).primary)
        assertEquals(punches().scheduled,punches(records=listOf(corrected)).scheduled)
    }
    @Test fun separateSessionsNeverLookLikeOneContinuousInterval() {
        val first=record.copy(clockOut=at("11:00"))
        val second=WorkSession(2,1,at("12:00"),clockOut=at("13:00"),state=ShiftState.COMPLETE)
        val result=punches(records=listOf(second,first))
        assertEquals("CLOCKED: 2 sessions · Expand for times",result.primary)
        assertEquals(listOf("Session 1 · CLOCKED: 4:00 AM – 11:00 AM","Session 2 · CLOCKED: 12:00 PM – 1:00 PM"),result.sessions)
    }
    @Test fun activeAndLunchNeverInventClockOut() {
        val working=record.copy(lunchStart=null,lunchEnd=null,clockOut=null,state=ShiftState.WORKING)
        assertEquals("CLOCKED IN: 4:00 AM · WORKING",punches(records=listOf(working)).primary)
        val lunch=working.copy(lunchStart=at("09:50"),state=ShiftState.ON_LUNCH)
        assertEquals("CLOCKED IN: 4:00 AM · ON LUNCH",punches(records=listOf(lunch)).primary)
        assertTrue(punches(records=listOf(record.copy(id=2),working)).primary.contains("WORKING · Clocked in 4:00 AM"))
    }
    @Test fun overnightDatesAndSecondsRetainDiscrepancies() {
        val overnight=shift.copy(scheduledStart=LocalTime.of(23,0),scheduledEnd=LocalTime.of(7,0))
        val session=WorkSession(1,1,at("23:00").plusMillis(123),clockOut=at("07:00").plusSeconds(86400),state=ShiftState.COMPLETE)
        assertEquals("CLOCKED: 23:00:00.123 – Oct 13, 07:00",punches(overnight,listOf(session),true).primary)
        assertNotNull(punches(overnight,listOf(session)).scheduled)
    }
    @Test fun missingAndUnscheduledRecordsDoNotFabricateClockedTimes() {
        assertEquals("Scheduled: 4:02 AM – 1:00 PM",punches(records=emptyList()).primary)
        assertEquals(punches(records=emptyList()),punches(records=listOf(record.copy(scheduledShiftId=null))))
        assertEquals("Loading recorded times…",punches(records=null).primary)
        assertTrue(punches(records=listOf(record.copy(lunchStart=null,lunchEnd=null))).primary.startsWith("CLOCKED:"))
    }
}
