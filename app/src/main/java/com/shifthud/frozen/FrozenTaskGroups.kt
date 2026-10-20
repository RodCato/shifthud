package com.shifthud.frozen

/** Read-only partition in original storage order. No task is copied, recreated or persisted here. */
data class FrozenTaskGroups(val active:List<FrozenTask>,val carryovers:List<FrozenTask>,val completed:List<FrozenTask>) {
    val total get()=active.size+carryovers.size+completed.size
    val remaining get()=active.size+carryovers.size
    val blocked get()=active.count { it.state==FrozenState.BLOCKED }
}
fun groupFrozenTasks(tasks:List<FrozenTask>)=FrozenTaskGroups(
    tasks.filter { it.state==FrozenState.TODO || it.state==FrozenState.IN_PROGRESS || it.state==FrozenState.BLOCKED },
    tasks.filter { it.state==FrozenState.CARRY_OVER },tasks.filter { it.state==FrozenState.COMPLETED })
