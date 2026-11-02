package com.shifthud.frozen

import java.time.LocalDate

/** Confirmed Friday field notes; inspection does not imply that KE freight exists. */
fun fridayFrozenTemplate(date:LocalDate):List<FrozenTask> = listOf(
    "Check for unworked Thursday KE freight" to "Inspect the freezer for Thursday KE/vendor deliveries and other outstanding freight. Do not assume a pallet is present. Note the origin/date and approximate remaining quantity if found. Freight already received and verified needs stocking, not another receiving transaction.",
    "Review expected Friday Frozen shipment" to "Check the expected shipment and identify upcoming promotional merchandise where applicable.",
    "Run priority backstock and shelf holes" to "When time permits before delivery, replenish available merchandise and clear freezer space.",
    "Prepare freezer and equipment" to "Ensure sufficient safe working space, accessible floats, and organized storage.",
    "Receive Friday Frozen delivery" to "Follow applicable receiving procedures and confirm required verification has been completed before working merchandise.",
    "Work incoming freight" to "Separate mixed pallets by aisle, stock home shelves, and stage appropriate promotional merchandise.",
    "Consolidate remaining backstock" to "Return excess merchandise to its designated product-family birds.",
    "Record unfinished freight and prepare handoff" to "Document remaining merchandise for the next Frozen associate. Use notes for its origin/date, approximate remaining quantity and any handoff details."
).mapIndexed { index,(title,description) ->
    FrozenTask("friday-$date-${index+1}",date,title,description,workflows=listOf(dailyWorkflowId(date)),section="TODAY'S RESPONSIBILITIES")
}
