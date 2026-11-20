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
        assertEquals(15,state.total);assertEquals(0,state.completed);assertEquals(15,state.tasks.size)
        assertEquals(7L,state.floatCount);assertEquals(day,state.date)
        assertFalse(state.tasks.any{it.title in listOf("Old","Dismissed","Other")})
    }
    @Test fun progressUpdatesFromPersistedCompletionAndHasAllDoneState() {
        val tasks=dailyFrozenTemplate(day)
        val half=tasks.mapIndexed {i,t->if(i<5)t.copy(state=FrozenState.COMPLETED) else t}
        assertEquals("5 of 10 completed",widgetFrozenState(half,FloatCounts(),day).summary)
        val all=widgetFrozenState(tasks.map{it.copy(state=FrozenState.COMPLETED)},FloatCounts(),day)
        assertEquals("All 10 tasks complete",all.summary);assertTrue(all.tasks.all {it.completed})
    }
    @Test fun undefinedDayIsEmptyWithoutInventingTasksOrCount() {
        val state=widgetFrozenState(emptyList(),FloatCounts(),day.plusDays(3))
        assertEquals(0,state.total);assertEquals(0L,state.floatCount)
        assertEquals("No recurring tasks or active carryovers",state.summary)
    }
    @Test fun fullChecklistKeepsFourUnfinishedBeforeSevenCompleted() {
        val tasks=(1..11).map {FrozenTask("task-$it",day,"Persisted task $it","",state=if(it<=7)FrozenState.COMPLETED else FrozenState.TODO)}
        val state=widgetFrozenState(tasks,FloatCounts(),day)
        assertEquals(11,state.total);assertEquals(7,state.completed)
        assertEquals(listOf("task-8","task-9","task-10","task-11"),state.tasks.take(4).map {it.id})
        assertTrue(state.tasks.drop(4).all {it.completed})
        assertEquals(11,visibleWidgetTaskCount(List(11){20f},220f,20f))
        assertEquals(3,visibleWidgetTaskCount(List(11){20f},80f,20f))
        assertEquals(0,visibleWidgetTaskCount(List(11){20f},0f,20f))
        assertEquals(1,visibleWidgetTaskCount(listOf(60f,20f,20f),90f,20f))
    }
    @Test fun completionReopenAndDateChangesRebuildFromCurrentFacts() {
        val task=FrozenTask("persisted",day,"Actual title","")
        val done=task.copy(state=FrozenState.COMPLETED)
        assertEquals(1,widgetFrozenState(listOf(done),FloatCounts(),day).completed)
        assertEquals(0,widgetFrozenState(listOf(task),FloatCounts(),day).completed)
        assertEquals(0,widgetFrozenState(listOf(done),FloatCounts(),day.plusDays(1)).total)
        val next=widgetFrozenState(listOf(task),FloatCounts(),day.plusDays(1))
        assertEquals("persisted",next.tasks.single().id)
        assertEquals(day.plusDays(1),next.date)
    }
}
