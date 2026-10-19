package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.ui.schedule.*
import com.shifthud.ui.time.decimalTime
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class PunchDiagnosticsTest {
    private val day=LocalDate.of(2026,10,12)
    private val shift=ScheduledShift(1,day,LocalTime.of(4,0),LocalTime.of(13,0))
    private fun at(t:String)=day.atTime(LocalTime.parse(t)).toInstant(ZoneOffset.UTC)
    private val record=WorkSession(1,1,at("04:02"),at("09:50"),at("10:47"),at("13:00:11"),ShiftState.COMPLETE)
    private fun details(records:List<WorkSession> = listOf(record))=punchDiagnostics(shift,records,at("14:00"),ZoneOffset.UTC,Locale.US)
    @Test fun octoberFixtureUsesAuthoritativeDurationsAndCleanOverview() {
        val p=schedulePunches(shift,listOf(record),ZoneOffset.UTC,Locale.US,false)
        assertEquals("CLOCKED: 4:02 AM – 1:00 PM",p.primary)
        assertEquals("Scheduled: 4:00 AM – 1:00 PM",p.scheduled)
        val d=details()!!
        val lines=d.sessions.single()
        assertTrue(lines.containsAll(listOf("Clock in: 04:02:00","Lunch out: 09:50:00","Lunch in: 10:47:00","Clock out: 13:00:11","Store duration: 08:58:11","Unpaid lunch: 00:57:00","Actual paid duration: 08:01:11")))
        assertTrue(lines.contains("Decimal paid hours: ${decimalTime(ShiftEngine().durations(record,1).paid,Locale.US)}"))
    }
    @Test fun fractionsRemainInCalculationsButNotTimestampText() {
        val r=record.copy(clockIn=at("04:00:00.900"),clockOut=at("13:00:00.100"))
        assertNull(schedulePunches(shift,listOf(r),ZoneOffset.UTC,Locale.US,false).scheduled)
        val d=details(listOf(r))!!
        assertEquals("Scheduled: 04:00:00 – 13:00:00",d.scheduled)
        assertTrue(d.precisionNote.contains("Subsecond timestamps exist"))
        assertTrue(d.sessions.single().contains("Actual paid duration: 08:02:59"))
        assertFalse(d.sessions.flatten().joinToString().contains(".900"))
        assertEquals(900000000,r.clockIn.nano)
    }
    @Test fun multipleSessionsAndLunchesStaySeparateAndAggregateExactly() {
        val a=record.copy(clockOut=at("11:00"))
        val b=WorkSession(2,1,at("12:00"),at("12:15"),at("12:30"),at("13:00"),ShiftState.COMPLETE)
        val d=details(listOf(b,a))!!
        assertEquals(2,d.sessions.size)
        assertTrue(d.sessions[0].contains("Lunch 1: 09:50:00 – 10:47:00"))
        assertTrue(d.sessions[1].contains("Lunch 2: 12:15:00 – 12:30:00"))
        assertTrue(d.totals.contains("Unpaid lunch: 01:12:00"))
        val actual=scheduleActuals(shift,listOf(a,b),60,at("14:00"),ZoneOffset.UTC,Locale.US,false)
        assertTrue(d.totals.contains("Decimal paid hours: ${decimalTime(actual.paid!!,Locale.US)}"))
    }
    @Test fun incompleteAndMissingLunchNeverInventPunches() {
        val r=record.copy(clockOut=null,lunchEnd=null,state=ShiftState.ON_LUNCH)
        val text=details(listOf(r))!!.sessions.single().joinToString()
        assertTrue(text.contains("Clock out: Not recorded"))
        assertTrue(text.contains("Lunch in: Not recorded"))
        assertTrue(text.contains("In progress"))
        assertTrue(details(listOf(record.copy(lunchStart=null,lunchEnd=null)))!!.sessions.single().contains("Lunch out: Not recorded"))
    }
    @Test fun futureUnscheduledAndOvernightFollowExistingAssociation() {
        assertNull(details(emptyList()))
        assertNull(details(listOf(record.copy(scheduledShiftId=null))))
        val overnight=record.copy(clockOut=at("01:00").plusSeconds(86400))
        assertTrue(details(listOf(overnight))!!.sessions.single().contains("Clock out: Oct 13, 2026 01:00:00"))
    }
}
