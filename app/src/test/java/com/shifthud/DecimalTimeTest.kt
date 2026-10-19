package com.shifthud

import com.shifthud.ui.time.*
import com.shifthud.ui.schedule.*
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class DecimalTimeTest {
    @Test fun familiarDurationsAndLocales() {
        listOf(480L to "8.00",484L to "8.07",495L to "8.25",510L to "8.50",525L to "8.75",2396L to "39.93",0L to "0.00").forEach { (minutes,label) -> assertEquals(label,decimalTime(Duration.ofMinutes(minutes),Locale.US)) }
        assertEquals("8,25",decimalTime(Duration.ofMinutes(495),Locale.GERMANY))
    }
    @Test fun halfUpUsesSecondsAndNanosWithoutIntermediateRounding() {
        assertEquals("0.00",decimalTime(Duration.ofSeconds(17,999999999),Locale.US))
        assertEquals("0.01",decimalTime(Duration.ofSeconds(18),Locale.US))
        assertEquals("8.08",decimalTime(Duration.ofHours(8).plusSeconds(270),Locale.US))
        assertEquals("8.07",decimalTime(Duration.ofHours(8).plusSeconds(269).plusNanos(999999999),Locale.US))
    }
    @Test fun roundedDaysNeedNotSumToRoundedWeek() {
        val day=Duration.ofSeconds(18)
        assertEquals("0.01",decimalTime(day,Locale.US))
        assertEquals("0.01",decimalTime(day.plus(day),Locale.US))
    }
    @Test fun comparisonIsExactBeforeRoundingAndMissingStaysMissing() {
        assertNull(timeComparison(Duration.ZERO,null,Locale.US))
        assertEquals("Difference (ShiftHUD − Publix): +0.08 decimal hours (approximately +5m)",timeComparison(Duration.ofMinutes(2396),"39.85".toBigDecimal(),Locale.US))
        assertEquals("Difference (ShiftHUD − Publix): -0.08 decimal hours (approximately -5m)",timeComparison(Duration.ofHours(8),"8.08".toBigDecimal(),Locale.US))
        // Subtracting rounded UI values would yield 0.01; exact difference rounds to zero.
        assertEquals("Difference (ShiftHUD − Publix): 0.00 decimal hours (approximately 0m)",timeComparison(Duration.ofSeconds(18),"0.005".toBigDecimal(),Locale.US))
    }
    @Test fun completedActiveOvernightAndProjectedRowsRetainExactSources() {
        val date=LocalDate.of(2026,10,17)
        val shift=ScheduledShift(1,date,LocalTime.of(23,0),LocalTime.of(7,0))
        val start=shift.start.toInstant(ZoneOffset.UTC)
        val record=WorkSession(1,1,start,clockOut=start.plusSeconds(8*3600+270),state=ShiftState.COMPLETE)
        val actual=scheduleActuals(shift,listOf(record),35,record.clockOut!!,ZoneOffset.UTC,Locale.US,false)
        assertEquals(Duration.between(start,record.clockOut),actual.paid)
        assertEquals("8.08",decimalTime(actual.paid!!,Locale.US))
        val active=record.copy(clockOut=null,state=ShiftState.WORKING)
        assertEquals(actual.paid,scheduleActuals(shift,listOf(active),35,record.clockOut,ZoneOffset.UTC,Locale.US,false).paid)
        val week=scheduleWeek(date)
        val model=schedulePlanning(week,listOf(shift),emptyList(),PlannerSettings().withWeek(date,35),Duration.ofHours(40),start.minusSeconds(60),ZoneOffset.UTC)
        val future=scheduleActuals(shift,emptyList(),35,start.minusSeconds(60),ZoneOffset.UTC,Locale.US,false,model.daily[1])
        assertEquals(model.plan.projected,future.paid)
        assertEquals("7.42",decimalTime(future.paid!!,Locale.US))
        assertTrue(schedulePlanning(week.moveWeeks(1),listOf(shift),emptyList(),PlannerSettings(),Duration.ofHours(40),start,ZoneOffset.UTC).daily.isEmpty())
    }
}
