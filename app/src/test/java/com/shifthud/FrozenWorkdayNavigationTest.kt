package com.shifthud

import com.shifthud.frozen.*
import com.shifthud.domain.model.ScheduledShift
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class FrozenWorkdayNavigationTest {
    private val today=LocalDate.of(2026,11,12)
    private fun shift(date:LocalDate)=ScheduledShift(date=date,scheduledStart=LocalTime.of(8,0),scheduledEnd=LocalTime.of(17,0))
    @Test fun todayWinsEvenWithFutureShifts(){assertEquals(today,initialFrozenWorkday(today,listOf(shift(today.plusDays(1)),shift(today))))}
    @Test fun skipsOffDaysAndUsesEarliestSavedDate(){assertEquals(today.plusDays(1),initialFrozenWorkday(today,listOf(shift(today.plusDays(3)),shift(today.plusDays(1)))));assertEquals(today.plusDays(3),initialFrozenWorkday(today,listOf(shift(today.plusDays(3)))))}
    @Test fun emptyOrOnlyPastScheduleFallsBackWithoutInferringTemplates(){assertEquals(today,initialFrozenWorkday(today,emptyList()));assertEquals(today,initialFrozenWorkday(today,listOf(shift(today.minusDays(1)))));assertNull(nextFrozenWorkday(today,emptyList()))}
    @Test fun nextShiftIsStrictlyFutureAndSupportsOvernight(){assertEquals(today.plusDays(1),nextFrozenWorkday(today,listOf(shift(today),shift(today.plusDays(1)).copy(scheduledStart=LocalTime.of(23,0),scheduledEnd=LocalTime.of(4,0)))));assertEquals(today,initialFrozenWorkday(today,listOf(shift(today).copy(scheduledEnd=LocalTime.of(8,0)))))}
    @Test fun movedDeletedAndDuplicateShiftsRecomputeOnNextEntry(){val a=shift(today.plusDays(1));val b=shift(today.plusDays(3));assertEquals(a.date,initialFrozenWorkday(today,listOf(a,a,b)));assertEquals(b.date,initialFrozenWorkday(today,listOf(a.copy(date=today.plusDays(5)),b)));assertEquals(today,initialFrozenWorkday(today,emptyList()))}
    @Test fun previewCarriesKnownWorkAcrossOffDaysWithoutMutation(){val source=wednesdayFrozenTemplate(today.minusDays(1)).first().copy(state=FrozenState.CARRY_OVER,notes="Keep notes");val tasks=listOf(source);val before=tasks.toList();val future=initialFrozenWorkday(today,listOf(shift(today.plusDays(3))));val first=frozenPlanningRows(tasks,future,today,true);assertTrue(first.any{row->row.sources.any{it.id==source.id}});assertEquals(first,frozenPlanningRows(tasks,future,today,true));assertEquals(before,tasks);assertEquals(first.size,first.map{it.task.id}.distinct().size)}
}
