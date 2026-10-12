package com.shifthud

import com.shifthud.ui.schedule.*
import com.shifthud.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class ScheduleWeekTest {
    private val week=scheduleWeek(LocalDate.of(2026,10,11))
    private fun shift(date:LocalDate,id:Long=1)=ScheduledShift(id,date,LocalTime.of(16,0),LocalTime.of(23,0))
    @Test fun defaultsToSaturdayFriday() { assertEquals(LocalDate.of(2026,10,10),week.start);assertEquals(LocalDate.of(2026,10,16),week.endInclusive) }
    @Test fun previousAndNextAreIndependentSelections() { assertEquals(week,week.moveWeeks(1).moveWeeks(-1));assertEquals(LocalDate.of(2026,10,17),week.moveWeeks(1).start) }
    @Test fun monthBoundary() { val w=scheduleWeek(LocalDate.of(2026,10,31));assertEquals(LocalDate.of(2026,11,6),w.endInclusive);assertEquals(LocalDate.of(2026,11,7),w.moveWeeks(1).start) }
    @Test fun yearBoundary() { val w=scheduleWeek(LocalDate.of(2026,12,31));assertEquals(LocalDate.of(2026,12,26),w.start);assertEquals(LocalDate.of(2027,1,1),w.endInclusive);assertEquals(LocalDate.of(2027,1,2),w.moveWeeks(1).start) }
    @Test fun headerIdentifiesBothYearsWhenNecessary() { val label=scheduleWeek(LocalDate.of(2026,12,31)).scheduleLabel(Locale.US);assertTrue(label.contains("2026"));assertTrue(label.contains("2027")) }
    @Test fun emptyWeekHasNoRows() { assertTrue(week.scheduleRows(emptyList()).isEmpty());assertTrue(week.scheduleRows(listOf(shift(week.moveWeeks(1).start))).isEmpty()) }
    @Test fun onlySelectedWeekAndBothBoundariesAreIncluded() { val rows=listOf(shift(week.start.minusDays(1)),shift(week.start,2),shift(week.endInclusive,3),shift(week.endInclusive.plusDays(1),4));assertEquals(listOf(2L,3L),week.scheduleRows(rows).map{it.id}) }
    @Test fun overnightBelongsToStartWeek() { val s=shift(week.endInclusive).copy(scheduledStart=LocalTime.of(23,0),scheduledEnd=LocalTime.of(4,0));assertEquals(listOf(s),week.scheduleRows(listOf(s)));assertTrue(week.moveWeeks(1).scheduleRows(listOf(s)).isEmpty()) }
    @Test fun importReplacementAndDeletionAreReflectedWithoutMovingWeek() { val next=week.moveWeeks(1);val imported=shift(next.start);assertTrue(week.scheduleRows(listOf(imported)).isEmpty());assertEquals(listOf(imported),next.scheduleRows(listOf(imported)));val replacement=imported.copy(scheduledStart=LocalTime.of(15,0));assertEquals(LocalTime.of(15,0),next.scheduleRows(listOf(replacement)).single().scheduledStart);assertTrue(next.scheduleRows(emptyList()).isEmpty());assertEquals(LocalDate.of(2026,10,17),next.start) }
}
