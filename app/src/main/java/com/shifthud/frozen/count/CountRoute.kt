package com.shifthud.frozen.count

import java.time.Instant

enum class WalkDirection(val label:String) { FRONT_BACK("Front → Back"), BACK_FRONT("Back → Front") }
enum class DoorState(val label:String) { OPEN("Not sampled"), SAMPLED("Location sampled"), SKIPPED("Skipped") }
enum class Submission(val label:String) {
    NOT_SUBMITTED("Not submitted"), SUBMITTED("Submitted in SIMS"), AWAITING("Awaiting management approval"), APPROVED("Approved / verified"), RECOUNT("Recount requested")
}
enum class SectionDepartment(val label:String) { FROZEN("Frozen"), MEAT("Meat"), OTHER("Other") }
enum class SectionKind(val label:String) { UPRIGHT("Upright freezer doors"), OPEN_CASE("Open freezer cases") }
data class PhysicalSection(val id:String,val label:String="",val department:SectionDepartment?=null,val kind:SectionKind?=null,val count:Int?=null) {
    val resolved get()=department!=null && (department!=SectionDepartment.FROZEN || (kind!=null && count!=null))
}
data class RouteSegment(val id:String,val aisle:Int,val side:String,val direction:WalkDirection?,val count:Int?=null,val excluded:Boolean=false,val sections:List<PhysicalSection>?=null) {
    val label get()="Aisle $aisle · $side"
    val configured get()=excluded || (sections?.let {it.isNotEmpty() && it.all {s->s.resolved} && (it.none {s->s.department==SectionDepartment.FROZEN} || direction!=null)} ?: (count!=null && direction!=null))
    fun forSectionEditing()=if(sections!=null)this else copy(count=null,sections=if(count==null) emptyList() else listOf(PhysicalSection("legacy", "Previous unclassified total",count=count)))
}
fun initialDoorMap()=listOf(
    RouteSegment("A10-R",10,"Right",WalkDirection.FRONT_BACK), RouteSegment("A10-L",10,"Left",WalkDirection.BACK_FRONT),
    RouteSegment("A11-R",11,"Right",WalkDirection.FRONT_BACK), RouteSegment("A11-L",11,"Left",WalkDirection.BACK_FRONT),
    RouteSegment("A12-R",12,"Right",null), RouteSegment("A12-L",12,"Left",null)
)
fun validateMap(segments:List<RouteSegment>) {
    require(segments.size==6 && segments.take(4).map {it.id}==initialDoorMap().take(4).map {it.id} && segments.takeLast(2).map {it.id}.toSet()==setOf("A12-R","A12-L")) {"Keep Aisles 10 and 11 in confirmed walking order and configure both Aisle 12 sides."}
    segments.forEach { segment->
        val original=initialDoorMap().single {it.id==segment.id}
        require(segment.aisle==original.aisle && segment.side==original.side)
        require(segment.count==null || segment.count in 1..200) {"Enter 1–200 doors, leave unknown, or explicitly exclude the segment."}
        segment.sections?.let {sections->
            require(sections.size<=100 && sections.map {it.id}.distinct().size==sections.size)
            sections.forEach {section->require(section.id.isNotBlank() && section.label.length<=120 && (section.count==null || section.count in 1..200)) {"Each section needs a unique identifier and 1–200 locations, or an unknown count."}}
            if(!segment.excluded && sections.any {it.department==SectionDepartment.FROZEN && it.count!=null})require(segment.direction!=null) {"Choose the walking direction for Frozen sections."}
            require(segment.count==null) {"Section maps cannot also contain an aisle-side total."}
        }
        if(segment.aisle!=12)require(segment.direction==original.direction)
        if(segment.count!=null && !segment.excluded)require(segment.direction!=null) {"Choose a walking direction for configured Aisle 12 sides."}
    }
}
data class CountDoor(val id:String,val segmentId:String,val number:Int,val products:Int=0,val state:DoorState=DoorState.OPEN,val note:String="",val sectionId:String?=null)
fun generateDoors(segments:List<RouteSegment>):List<CountDoor> {
    validateMap(segments)
    return segments.filter {!it.excluded}.flatMap { segment->
        if(segment.sections==null) {
            if(!segment.configured) emptyList() else (1..requireNotNull(segment.count)).map {n->CountDoor("${segment.id}-${n.toString().padStart(2,'0')}",segment.id,n)}
        } else {
            // Sections are entered in walking order; non-Frozen and unresolved sections never consume a number.
            var number=0
            if(segment.direction==null) emptyList() else segment.sections.filter {it.department==SectionDepartment.FROZEN && it.kind!=null && it.count!=null}.flatMap {section->
                (1..requireNotNull(section.count)).map {number++;CountDoor("${segment.id}-${number.toString().padStart(2,'0')}",segment.id,number,sectionId=section.id)}
            }
        }
    }
}
data class CountSession(val id:String,val started:Instant,val ended:Instant?=null,val startDoor:String?,val endDoor:String?=null,
    val products:Int=0,val submission:Submission=Submission.NOT_SUBMITTED,val note:String="")
data class CountCycle(val number:Int,val started:Instant,val finished:Instant?=null,val target:Int,
    val map:List<RouteSegment>,val doors:List<CountDoor>,val current:String?,val paused:Boolean=false,val sessions:List<CountSession>) {
    val ownershipKnown get()=map.all {it.sections!=null || it.excluded || it.count==null}
    val sampled get()=doors.count {it.state==DoorState.SAMPLED}
    val skipped get()=doors.count {it.state==DoorState.SKIPPED}
    val partial get()=doors.count {it.state==DoorState.OPEN && it.products>0}
    val products get()=doors.sumOf {it.products}
    val mapComplete get()=map.all {it.configured}
    val covered get()=ownershipKnown && mapComplete && doors.isNotEmpty() && sampled==doors.size
    val currentDoor get()=doors.singleOrNull {it.id==current}
    fun location(door:CountDoor):String {
        val segment=map.single {it.id==door.segmentId}
        val section=segment.sections?.singleOrNull {it.id==door.sectionId}
        return segment.label+(section?.let {" · ${it.label.ifBlank {"Section ${segment.sections!!.indexOf(it)+1}"}}"} ?: "")+" · ${if(section?.kind==SectionKind.OPEN_CASE) "Case" else "Door"} ${door.number}"
    }
    fun nextOpen(after:String?):CountDoor? {
        val i=doors.indexOfFirst {it.id==after}
        return (doors.drop(i+1)+doors.take((i+1).coerceAtLeast(0))).firstOrNull {it.id!=after && it.state==DoorState.OPEN}
    }
}
data class CountRouteData(val revision:Long=0,val map:List<RouteSegment> = initialDoorMap(),val target:Int=5,val cycles:List<CountCycle> = emptyList()) {
    val active get()=cycles.singleOrNull {it.finished==null}
}
fun mapChanges(before:List<RouteSegment>,after:List<RouteSegment>):List<String> = before.map {old->old to after.single {it.id==old.id}}.filter {it.first!=it.second}.map { (old,new)->
    fun describe(s:RouteSegment):String {
        if(s.excluded)return "Excluded"
        val sections=s.sections?.mapIndexed {index,section->"${section.label.ifBlank {"Section ${index+1}"}}: ${section.department?.label ?: "Ownership unknown"} · ${section.kind?.label ?: "Type unknown"} · ${section.count ?: "Count unknown"}"}?.joinToString("; ")?.ifBlank {"Not configured"}
        return "${s.direction?.label ?: "Direction not configured"} · ${sections ?: s.count?.let {"$it unclassified locations"} ?: "Not configured"}"
    }
    "${old.id}: ${describe(old)} → ${describe(new)}"
} + if(before.takeLast(2).map {it.id}!=after.takeLast(2).map {it.id}) listOf("Aisle 12 walking order: ${before.takeLast(2).joinToString {it.side}} → ${after.takeLast(2).joinToString {it.side}}") else emptyList()
