package com.shifthud

import com.shifthud.frozen.*
import com.shifthud.domain.model.ScheduledShift
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class FrozenPlanningTest {
    private val friday=LocalDate.of(2026,11,6)
    @Test fun fridayContainsEightIndependentConditionalResponsibilities() {
        val template=fridayFrozenTemplate(friday)
        assertEquals(8,template.size);assertEquals(8,template.map{it.id}.toSet().size)
        assertEquals("Check for unworked Thursday KE freight",template.first().title)
        assertTrue(template.first().description.contains("Do not assume a pallet is present"))
        assertTrue(template.first().description.contains("not another receiving transaction"))
        assertTrue(template[4].description.contains("verification has been completed"))
        assertTrue(template.all{it.state==FrozenState.TODO && it.history.isEmpty() && it.notes.isEmpty()})
        assertEquals(template,dailyFrozenTemplate(friday))
    }
    @Test fun futureRowsUpdateWithSourceCompletionWithoutMutatingRecords() {
        val source=FrozenTask("manual",friday,"Freight","",notes="Synthetic quantity")
        val original=listOf(source)
        val sunday=friday.plusDays(2)
        assertTrue(frozenPlanningRows(original,sunday,friday).any{it.task.id==source.id})
        assertEquals(listOf(source),original)
        assertFalse(frozenPlanningRows(listOf(source.copy(state=FrozenState.COMPLETED)),sunday,friday).any{it.task.id==source.id})
        assertFalse(frozenPlanningRows(listOf(source.copy(carryoverExcluded=true)),sunday,friday).any{it.task.id==source.id})
        assertFalse(frozenPlanningRows(listOf(source.copy(resolvedByOtherAt=Instant.EPOCH)),sunday,friday).any{it.task.id==source.id})
    }
    @Test fun projectedCarryoverDoesNotInventInterveningFutureWork() {
        val today=friday.minusDays(4)
        val rows=frozenPlanningRows(tuesdayFrozenTemplate(today.plusDays(1)),friday,today)
        assertEquals(8,rows.size)
        assertTrue(rows.all{it.sources.all{source->source.date==friday}})
    }
    @Test fun weekAlwaysContainsSaturdayThroughFridayIncludingYearBoundary() {
        val selected=LocalDate.of(2027,1,1)
        val days=frozenPlanningWeek(emptyList(),emptyList(),selected,friday)
        assertEquals(LocalDate.of(2026,12,26),days.first().date)
        assertEquals(selected,days.last().date);assertEquals(7,days.size)
        assertEquals(DayOfWeek.SATURDAY,days.first().date.dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY,days.last().date.dayOfWeek)
        assertTrue(days.all{it.preview})
    }
    @Test fun schedulesAreExactMultipleOvernightSpansAndNotAssumedOff() {
        val zone=ZoneId.of("America/Chicago")
        val shifts=listOf(ScheduledShift(1,friday,LocalTime.of(23,0),LocalTime.of(4,0)),ScheduledShift(2,friday,LocalTime.of(8,0),LocalTime.of(10,0)))
        val days=frozenPlanningWeek(emptyList(),shifts,friday,friday)
        val day=days.single{it.date==friday}
        assertEquals(Duration.ofHours(7),day.scheduledSpan(zone));assertEquals(2,day.scheduledShifts.size)
        val thursday=days.single{it.date.dayOfWeek==DayOfWeek.THURSDAY}
        assertFalse(thursday.hasTemplate);assertTrue(thursday.scheduledShifts.isEmpty())
        assertEquals("No defined template",thursday.focus)
        val saturday=days.first().date
        val workedSaturday=frozenPlanningWeek(emptyList(),listOf(shifts.first().copy(date=saturday)),saturday,friday).first()
        assertEquals(1,workedSaturday.scheduledShifts.size);assertFalse(workedSaturday.hasTemplate)
    }
    @Test fun scheduledSpanUsesActualZoneAcrossDst() {
        val date=LocalDate.of(2026,11,1)
        val shift=ScheduledShift(1,date,LocalTime.MIDNIGHT,LocalTime.of(4,0))
        val day=frozenPlanningWeek(emptyList(),listOf(shift),date,date).single{it.date==date}
        assertEquals(Duration.ofHours(5),day.scheduledSpan(ZoneId.of("America/Chicago")))
    }
    @Test fun weeklyCountsDistinguishPersistedCompletionAndProjectedCarryovers() {
        val source=FrozenTask("source",friday,"Freight","")
        val completed=FrozenTask("done",friday,"Done","",state=FrozenState.COMPLETED)
        val days=frozenPlanningWeek(listOf(source,completed),emptyList(),friday,friday)
        assertEquals(1,days.last().completed);assertEquals(1,days.last().outstanding)
        val future=frozenPlanningWeek(listOf(source,completed),emptyList(),friday.plusDays(2),friday)
        assertEquals(1,future.first().carryovers)
        assertTrue(future.first().preview)
        assertFalse(future.first().hasTemplate)
    }
}
