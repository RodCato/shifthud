package com.shifthud.frozen

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val WEDNESDAY_PREFIX="wednesday:"
fun wednesdayWorkflowId(date:LocalDate)="$WEDNESDAY_PREFIX$date"
fun isWednesdayWorkflow(id:String)=id.startsWith(WEDNESDAY_PREFIX)
fun frozenWorkflowDate(id:String):LocalDate=LocalDate.parse(id.removePrefix(WEDNESDAY_PREFIX))
fun frozenWorkflowLabel(id:String)=if(isWednesdayWorkflow(id)) "Wednesday — Frozen Truck" else "Tuesday workflow"

data class TruckArrivalEvent(val arrived:Boolean,val at:Instant,val zone:String)
data class FrozenTruckWorkflow(val date:LocalDate,val taskIds:List<String>,val events:List<TruckArrivalEvent> = emptyList()) {
    val arrival get()=events.lastOrNull()?.takeIf { it.arrived }
}

internal data class TruckStep(val type:String,val section:String,val title:String,val reference:String="")
internal val truckSteps=listOf(
    TruckStep("inspect-freezer","BEFORE TRUCK","Inspect freezer and ad wall","Check freezer organization, available floor space, backstock conditions, and promotional merchandise."),
    TruckStep("inspect-endcap","BEFORE TRUCK","Inspect new endcap","Verify product presentation and promotional price tags following Tuesday's transition."),
    TruckStep("replenish-ad-wall","BEFORE TRUCK","Replenish ad wall","Prioritize current promotional merchandise and keep advertised products available."),
    TruckStep("review-order","BEFORE TRUCK","Review Wednesday order sheet","Check expected Frozen case count and anticipated pallet count when available."),
    TruckStep("check-shipped","BEFORE TRUCK","Check final shipped quantities","Management may receive a warehouse email containing final shipped cases and pallets. Do not assume it identifies individual SKU shortages."),
    TruckStep("prepare-receiving","BEFORE TRUCK","Prepare floats and receiving space","Stage appropriate equipment and ensure receiving areas are accessible. No exact delivery time is required."),
    TruckStep("receive-freight","TRUCK RECEIVING","Receive Frozen freight","Follow authorized store receiving procedures."),
    TruckStep("separate-departments","TRUCK RECEIVING","Separate merchandise by department","Frozen deliveries may include merchandise for Bakery or Meat. Follow store receiving procedures."),
    TruckStep("stage-freight","TRUCK RECEIVING","Stage Frozen merchandise for stocking","Keep receiving areas and freezer access clear."),
    TruckStep("identify-promotional","TRUCK RECEIVING","Identify promotional merchandise","Prioritize current ad and BOGO products for appropriate shelf replenishment and ad-wall staging."),
    TruckStep("work-freight","STOCKING","Work Frozen freight onto home shelves"),
    TruckStep("replenish-promotional","STOCKING","Replenish current promotional merchandise"),
    TruckStep("stage-ad-stock","STOCKING","Stage remaining ad merchandise","Keep promotional stock accessible on the ad wall for replenishment during the sale."),
    TruckStep("consolidate-backstock","STOCKING","Consolidate remaining backstock","Organize excess merchandise onto appropriate backstock birds. Consolidate partial cartons when appropriate. Do not assume significant backstock from every delivery."),
    TruckStep("organize-freezer","STOCKING","Verify freezer organization","Keep walkways and work areas accessible."),
    TruckStep("damages-donations","AFTER TRUCK","Process damages and donations","Use authorized SIMS procedures when a scanner is available."),
    TruckStep("random-counts","AFTER TRUCK","Perform Random Counts","Prefer completing backstock and inventory adjustments before counts."),
    TruckStep("inventory-followup","AFTER TRUCK","Review remaining inventory issues","Identify holes, excess stock, and items needing follow-up."),
    TruckStep("thursday-handoff","AFTER TRUCK","Prepare Thursday handoff","Record unfinished work and outstanding inventory questions.")
)
fun wednesdayFrozenTemplate(date:LocalDate)=truckSteps.map { step ->
    FrozenTask("wednesday-$date-${step.type}",date,step.title,step.reference,
        workflows=listOf(wednesdayWorkflowId(date)),section=step.section)
}

/** Only known template identities imply a task type. User titles never establish equivalence. */
internal fun frozenTruckTaskType(task:FrozenTask):String? {
    val legacy=mapOf("frozen-2026-10-19-1" to "inspect-freezer","frozen-2026-10-19-4" to "replenish-ad-wall","frozen-2026-10-19-7" to "random-counts")
    return legacy[task.id] ?: truckSteps.firstOrNull { task.id=="wednesday-${task.date}-${it.type}" }?.type
}
fun truckSectionOrder(arrived:Boolean)=if(arrived)
    listOf("TRUCK RECEIVING","STOCKING","BEFORE TRUCK","AFTER TRUCK")
else listOf("BEFORE TRUCK","TRUCK RECEIVING","STOCKING","AFTER TRUCK")

fun truckArrivalText(event:TruckArrivalEvent,locale:java.util.Locale,use24Hour:Boolean):String =
    event.at.atZone(ZoneId.of(event.zone)).format(java.time.format.DateTimeFormatter.ofPattern(if(use24Hour) "HH:mm" else "h:mm a",locale))
