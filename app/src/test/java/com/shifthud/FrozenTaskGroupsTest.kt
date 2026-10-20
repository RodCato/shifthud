package com.shifthud

import com.shifthud.frozen.*
import org.junit.Assert.*
import org.junit.Test

class FrozenTaskGroupsTest {
    @Test fun partitionsAllStatesOnceInOriginalOrderWithoutChangingRecords() {
        val states=listOf(FrozenState.COMPLETED,FrozenState.BLOCKED,FrozenState.CARRY_OVER,FrozenState.TODO,FrozenState.COMPLETED,FrozenState.IN_PROGRESS)
        val tasks=mondayFrozenTemplate().take(states.size).mapIndexed { i,t -> t.copy(state=states[i],notes="Note $i") }
        val g=groupFrozenTasks(tasks)
        assertEquals(listOf(tasks[1],tasks[3],tasks[5]),g.active)
        assertEquals(listOf(tasks[0],tasks[4]),g.completed)
        assertEquals(listOf(tasks[2]),g.carryovers)
        assertEquals(tasks.map { it.id }.toSet(),(g.active+g.carryovers+g.completed).map { it.id }.toSet())
        assertEquals(6,g.total);assertEquals(4,g.remaining);assertEquals(1,g.blocked)
    }
    @Test fun completingReopeningAndRemovingCarryoverRepartitionsSameIds() {
        val task=mondayFrozenTemplate().first().copy(notes="Finish Tuesday")
        for(state in FrozenState.entries) {
            val g=groupFrozenTasks(listOf(task.copy(state=state)))
            assertEquals(1,g.total)
            assertEquals(listOf(task.id),(g.active+g.carryovers+g.completed).map { it.id })
            assertEquals(if(state==FrozenState.COMPLETED) 0 else 1,g.remaining)
        }
        val reopened=groupFrozenTasks(listOf(task.copy(state=FrozenState.TODO)))
        assertEquals(listOf(task),reopened.active)
    }
    @Test fun customTasksAndEmptyGroupsHaveAccurateCounts() {
        val custom=mondayFrozenTemplate().first().copy(id="custom",title="My task",state=FrozenState.CARRY_OVER)
        val g=groupFrozenTasks(mondayFrozenTemplate()+custom)
        assertEquals(11,g.total);assertEquals(11,g.remaining);assertEquals(1,g.carryovers.size)
        assertEquals(0,groupFrozenTasks(emptyList()).total)
    }
}
