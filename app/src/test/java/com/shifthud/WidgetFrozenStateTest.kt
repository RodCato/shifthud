package com.shifthud

import com.shifthud.frozen.*
import com.shifthud.widget.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class WidgetFrozenStateTest {
    private val day=LocalDate.of(2026,11,2)
    @Test fun summaryUsesMergedSourcesAndExcludesArchivedDismissedAndResolved() {
        val tasks=dailyFrozenTemplate(day)+sundayFrozenTemplate(day.minusDays(1))+listOf(
            FrozenTask("old",LocalDate.of(2026,10,19),"Old","",carryoverArchived=true),
            FrozenTask("dismissed",day,"Dismissed","",carryoverExcluded=true),
            FrozenTask("other",day,"Other","",resolvedByOtherAt=Instant.EPOCH))
        val state=widgetFrozenState(tasks,FloatCounts(mapOf(day to 7)),day)
        assertEquals(15,state.total);assertEquals(0,state.completed);assertEquals(3,state.unfinished.size)
        assertEquals(7L,state.floatCount);assertEquals(day,state.date)
        assertFalse(state.unfinished.any{it in listOf("Old","Dismissed","Other")})
    }
    @Test fun progressUpdatesFromPersistedCompletionAndHasAllDoneState() {
        val tasks=dailyFrozenTemplate(day)
        val half=tasks.mapIndexed {i,t->if(i<5)t.copy(state=FrozenState.COMPLETED) else t}
        assertEquals("5 of 10 completed",widgetFrozenState(half,FloatCounts(),day).summary)
        val all=widgetFrozenState(tasks.map{it.copy(state=FrozenState.COMPLETED)},FloatCounts(),day)
        assertEquals("All 10 tasks complete",all.summary);assertTrue(all.unfinished.isEmpty())
    }
    @Test fun undefinedDayIsEmptyWithoutInventingTasksOrCount() {
        val state=widgetFrozenState(emptyList(),FloatCounts(),day.plusDays(3))
        assertEquals(0,state.total);assertEquals(0L,state.floatCount)
        assertEquals("No recurring tasks or active carryovers",state.summary)
    }
}
