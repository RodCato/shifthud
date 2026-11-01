package com.shifthud.frozen

import java.time.LocalDate

/** User-provided Sunday field notes; independent tasks, never automatic completion. */
fun sundayFrozenTemplate(date: LocalDate): List<FrozenTask> = listOf(
    "Review upcoming promotional items" to "Review the upcoming promotional items in Supply Chain Workbench using the FF Frozen expected list. Identify the asterisked ad items.",
    "Replenish current ad wall merchandise" to "Replenish the current ad wall onto the sales floor. Return excess merchandise to the top racks of its respective product-family backstock birds.",
    "Run Frozen backstock" to "Run regular backstock while waiting for the delivery, prioritizing available shelf space and maintaining organized birds.",
    "Prepare freezer and equipment" to "Prepare the freezer and equipment for incoming pallets, keeping receiving space and access clear.",
    "Receive Frozen shipment" to "Receive the Frozen shipment and ensure required receiving/invoice verification is completed by authorized personnel before merchandise is worked.",
    "Break down and work mixed pallets" to "Break down mixed pallets, separating Aisle 10, Aisle 11, and Aisle 12 merchandise as appropriate. Work home shelves first and stage upcoming promotional merchandise on the ad wall.",
    "Consolidate backstock and record unfinished freight" to "Consolidate remaining backstock onto the appropriate birds and record any unfinished freight for Monday."
).mapIndexed { index, (title, description) ->
    FrozenTask("sunday-$date-${index+1}", date, title, description,
        workflows=listOf(dailyWorkflowId(date)), section="TODAY'S RESPONSIBILITIES")
}
