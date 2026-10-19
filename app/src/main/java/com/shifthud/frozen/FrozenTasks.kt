package com.shifthud.frozen

import java.time.Instant
import java.time.LocalDate

enum class FrozenState(val label: String) {
    TODO("To do"), IN_PROGRESS("In progress"), COMPLETED("Completed"), BLOCKED("Blocked"), CARRY_OVER("Carry over")
}
data class FrozenEvent(val state: FrozenState, val at: Instant)
data class FrozenTask(val id: String, val date: LocalDate, val title: String, val description: String,
    val state: FrozenState = FrozenState.TODO, val notes: String = "", val history: List<FrozenEvent> = emptyList())

val FROZEN_MONDAY: LocalDate = LocalDate.of(2026,10,19)
const val FROZEN_TITLE = "Frozen — Monday Solo Shift"
const val FROZEN_REFERENCE_NOTE = "Personal field notes, not official Publix SOPs. Follow management instructions and store policy."

/** One explicitly requested checklist, never generated from dates, schedules or clock state. */
fun mondayFrozenTemplate(): List<FrozenTask> = listOf(
    "Check freezer and ad wall" to "Check conditions, priorities and ad wall needs. Confirm the day's priorities with management.",
    "Transfer remaining Bakery pallet" to "Confirm the remaining Bakery pallet and its destination with the appropriate department before transferring.",
    "Run Frozen backstock" to "Check green-marked shelf holes.\nReplenish shelves from appropriate birds.\nConsolidate partial cartons.\nKeep loose merchandise protected in cartons.\nKeep freezer walkways clear.",
    "Replenish ad wall merchandise" to "Review ad wall holes and replenish appropriate merchandise. Confirm placement and priorities with management.",
    "Process damages" to "Use authorized SIMS scanner.\nScan individual items when practical.\nSelect Adjustments and correct damage type.\nFinalize adjustments.\nBag and dispose using store procedures.",
    "Process donations" to "Confirm product eligibility.\nHave banana boxes ready.\nProcess authorized SIMS adjustments.\nPack eligible merchandise.\nTransfer completed boxes to designated meat-freezer donation location.",
    "Perform Random Counts" to "Complete backstock and inventory removals first.\nCreate Frozen Random Counts list in SIMS.\nReconcile sales-floor and backstock quantities.\nFinalize when authorized.",
    "Review MASC" to "Review shelf capacity and case pack.\nAvoid unnecessary excess backstock.\nChange settings only when authorized.",
    "Check missing price tags" to "Locate yellow missing-tag markers.\nUse SIMS Price Tags.\nSelect correct internal tag format.\nFinalize list.\nPrint through authorized Sticker Shop workflow.",
    "Prepare Tuesday handoff" to "Review unfinished work, blockers and notes. Mark unfinished tasks Carry to next shift. Do not assign tasks to another employee."
).mapIndexed { index,(title,description) -> FrozenTask("frozen-2026-10-19-${index+1}",FROZEN_MONDAY,title,description) }
