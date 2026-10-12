package com.shifthud

import com.shifthud.domain.scheduleimport.*
import com.shifthud.domain.model.*
import com.shifthud.ui.scheduleimport.scheduleOcrRows
import com.shifthud.ui.paystub.OcrPiece
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class ScheduleImportTest {
    private val week=LocalDate.of(2026,10,17)
    private val fixture="""Week October 17–23, 2026
Saturday, Oct 17 4:00 PM–11:00 PM 6h
Sunday, Oct 18 10:00 AM–6:00 PM 7h
Monday, Oct 19 8:00 AM–5:00 PM 8h
Tuesday, Oct 20 8:00 AM–5:00 PM 8h
Wednesday, Oct 21 8:00 AM–5:00 PM 8h
Displayed weekly net: 37 hours"""
    @Test fun realFixturePopulatesEveryVisibleShift() {
        val result=extractSchedule(fixture)
        assertEquals(week,result.week);assertFalse(result.requiresWeekConfirmation)
        assertEquals(5,result.rows.size);assertEquals(37*60,result.weeklyNetMinutes)
        assertEquals(LocalTime.of(16,0),result.rows.first().start)
        assertEquals(listOf(6,7,8,8,8),result.rows.map{it.netMinutes!!/60})
        assertTrue(result.rows.all{it.lunch==60 && it.lunchSource==LunchSource.INFERRED_NET && it.confidence==Confidence.HIGH})
        assertEquals(37*60L,result.rows.sumOf{Duration.between(it.shift()!!.start,it.shift()!!.end).toMinutes()-it.lunch!!})
    }
    @Test fun croppedRowsNeverCreateMissingDaysOff() { assertEquals(5,extractSchedule(fixture).rows.size);assertTrue(extractSchedule(fixture).rows.none{it.off}) }
    @Test fun supportedTimesPreserveAmPm() {
        mapOf("4 AM" to 4,"4:00 AM" to 4,"04:00" to 4,"4p" to 16,"4:00 PM" to 16,"12 AM" to 0,"12 PM" to 12,"23:00" to 23).forEach{(s,h)->assertEquals(s,LocalTime.of(h,0),parseScheduleTime(s))}
    }
    @Test fun ambiguousAndInvalidTimesRemainUnknown() { listOf("4","4:00","13 PM","25:00","4:99 PM","").forEach{assertNull(parseScheduleTime(it))} }
    @Test fun overnightUsesNextDateAndInfersLunch() {
        val c=extractSchedule("Saturday Oct 17 10 PM–6 AM net 7h",week).rows.single()
        assertEquals(week.plusDays(1),c.shift()!!.end.toLocalDate());assertEquals(60,c.lunch)
    }
    @Test fun missingYearDoesNotGuessCurrentYear() {
        val r=extractSchedule("Week October 17–23\nSaturday Oct 17 4p–11p 6h")
        assertNull(r.week);assertTrue(r.requiresWeekConfirmation);assertNull(r.rows.single().date)
        assertEquals(week,extractSchedule("Saturday Oct 17 4p–11p 6h",week).rows.single().date)
    }
    @Test fun decemberJanuaryRolloverIsDateAware() {
        val r=extractSchedule("Week Dec 26, 2026–Jan 1, 2027\nSaturday Dec 26 8 AM–5 PM 8h\nFriday Jan 1 8 AM–5 PM 8h")
        assertEquals(LocalDate.of(2026,12,26),r.week);assertEquals(LocalDate.of(2027,1,1),r.rows.last().date)
    }
    @Test fun trailingJanuaryYearAppliesToDecemberPreviousYear() {
        val r=extractSchedule("Week Dec 26–Jan 1, 2027\nSaturday Dec 26 8 AM–5 PM 8h")
        assertEquals(LocalDate.of(2026,12,26),r.week)
    }
    @Test fun weekdayMismatchRequiresReviewWithoutMovingDate() {
        val c=extractSchedule("Monday Oct 17 8 AM–5 PM 8h",week).rows.single()
        assertEquals(week,c.date);assertEquals(Confidence.REVIEW_REQUIRED,c.confidence)
    }
    @Test fun wrongWeekStaysUnknownRatherThanShifting() { assertNull(extractSchedule("Saturday Oct 24 8 AM–5 PM 8h",week).rows.single().date) }
    @Test fun lunchDefaultIsClearlyDistinguished() {
        val c=extractSchedule("Saturday Oct 17 8 AM–5 PM",week,50).rows.single()
        assertEquals(50,c.lunch);assertEquals(LunchSource.DEFAULT,c.lunchSource);assertEquals(Confidence.HIGH,c.confidence);assertNull(c.shift()!!.plannedLunchMinutes);assertNull(c.shift()!!.employerLunchMinutes)
    }
    @Test fun explicitLunchWinsAndDisagreementWarns() {
        val c=extractSchedule("Saturday Oct 17 8 AM–5 PM net 8h lunch 45 min",week).rows.single()
        assertEquals(45,c.lunch);assertEquals(LunchSource.EXPLICIT,c.lunchSource);assertTrue(c.warnings.any{it.contains("disagree")})
    }
    @Test fun impossibleNetDoesNotProduceLunch() {assertNull(extractSchedule("Saturday Oct 17 8 AM–5 PM net 10h",week).rows.single().lunch)}
    @Test fun decimalPaidHoursAreExact() { assertEquals(450,decimalHoursMinutes("7.50"));assertNull(decimalHoursMinutes("7.333"));assertNull(decimalHoursMinutes("")) }
    @Test fun explicitOffDoesNotCreateShift() { val c=extractSchedule("Thursday Oct 22 OFF",week).rows.single();assertTrue(c.off);assertNull(c.shift()) }
    @Test fun splitDateRowsStayAssociated() { val r=extractSchedule("Saturday\nOct 17\n4 PM–11 PM\n6h",week);assertEquals(1,r.rows.size);assertEquals(week,r.rows.single().date) }
    @Test fun geometryReadsSeparateCalendarColumns() {
        val p=listOf(OcrPiece("Saturday Oct 17",0,30,100,40),OcrPiece("Sunday Oct 18",200,30,300,40),OcrPiece("4 PM–11 PM 6h",0,60,100,70),OcrPiece("10 AM–6 PM 7h",200,60,300,70))
        val rows=extractSchedule(scheduleOcrRows(p),week).rows
        assertEquals(2,rows.size);assertEquals(LocalTime.of(16,0),rows.first().start);assertEquals(LocalTime.of(10,0),rows.last().start);assertTrue(rows.all{it.confidence==Confidence.REVIEW_REQUIRED})
    }
    @Test fun duplicateIgnoresLunchOverrideAndPreservesIt() {val s=extractSchedule(fixture).rows.first().shift()!!;assertEquals(ImportKind.EXACT_DUPLICATE,classify(s,listOf(s.copy(id=5,plannedLunchMinutes=45))).kind)}
    @Test fun changedSameDayRequiresExplicitUpdate() { val s=extractSchedule(fixture).rows.first().shift()!!;assertEquals(ImportKind.POSSIBLE_UPDATE,classify(s,listOf(s.copy(id=5,scheduledStart=LocalTime.of(15,0)))).kind) }
    @Test fun overnightOverlapIsConflictAcrossDates() { val a=ScheduledShift(date=week,scheduledStart=LocalTime.of(22,0),scheduledEnd=LocalTime.of(6,0));val b=a.copy(date=week.plusDays(1),scheduledStart=LocalTime.of(5,0),scheduledEnd=LocalTime.NOON);assertEquals(ImportKind.CONFLICT,classify(b,listOf(a)).kind) }
    @Test fun unknownOrEmptyInputDoesNotInventShifts() {assertTrue(extractSchedule("Store 123 Department Grocery").rows.isEmpty());assertEquals(ImportKind.INVALID,classify(null,emptyList()).kind)}
    @Test(expected=IllegalArgumentException::class) fun nonSaturdayWeekRejected() {extractSchedule(fixture,week.plusDays(1))}
    @Test fun missingRangeEndRequiresWeekConfirmation() { assertTrue(extractSchedule("Week October 17, 2026\nSaturday Oct 17 4 PM–11 PM 6h").requiresWeekConfirmation) }
    @Test fun mismatchedRangeEndRequiresConfirmation() { assertTrue(extractSchedule("Week October 17–24, 2026\nSaturday Oct 17 4 PM–11 PM 6h").requiresWeekConfirmation) }
    @Test fun separateStartEndColumnsPopulateWithoutDash() {
        val c=extractSchedule("Saturday Oct 17\nStart 4:00 PM\nEnd 11:00 PM\nNet 6h",week).rows.single()
        assertEquals(LocalTime.of(16,0),c.start);assertEquals(LocalTime.of(23,0),c.end);assertEquals(60,c.lunch)
    }
    @Test fun croppedEndStillPrefillsKnownStart() { val c=extractSchedule("Saturday Oct 17 Start 4 PM",week).rows.single();assertEquals(LocalTime.of(16,0),c.start);assertNull(c.end) }
    @Test fun hoursAndMinutesAndUnlabeledDecimal() {
        assertEquals(450,extractSchedule("Saturday Oct 17 8 AM–5 PM net 7h 30m",week).rows.single().netMinutes)
        val c=extractSchedule("Saturday Oct 17 8 AM–5 PM 7.50",week).rows.single()
        assertEquals(450,c.netMinutes);assertEquals(90,c.lunch);assertEquals(Confidence.REVIEW_REQUIRED,c.confidence)
    }

}
