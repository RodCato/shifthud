package com.shifthud.domain.importing

import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.HistoricalShiftInput
import java.time.*

/** User-supplied Passport punches. Never inserted by startup or a database migration. */
data class BackfillShift(val input: HistoricalShiftInput, val store: String, val employerHours: String) {
    val date get() = input.date
}
val PUBLIX_BACKFILL = listOf(
    BackfillShift(HistoricalShiftInput(LocalDate.of(2026,9,25),LocalTime.of(12,13),LocalTime.of(14,22)),"1425","2.15"),
    BackfillShift(HistoricalShiftInput(LocalDate.of(2026,9,26),LocalTime.of(8,0),LocalTime.of(13,0)),"1425","5.00"),
    BackfillShift(HistoricalShiftInput(LocalDate.of(2026,9,28),LocalTime.of(4,0),LocalTime.of(13,0),LocalTime.of(9,54),LocalTime.of(10,49)),"1425","8.08"),
    BackfillShift(HistoricalShiftInput(LocalDate.of(2026,9,29),LocalTime.of(8,0),LocalTime.of(17,1),LocalTime.of(11,23),LocalTime.of(12,16)),"1425","8.13"),
    BackfillShift(HistoricalShiftInput(LocalDate.of(2026,9,30),LocalTime.of(4,0),LocalTime.of(13,0),LocalTime.of(9,55),LocalTime.of(10,50)),"1425","8.09"),
    BackfillShift(HistoricalShiftInput(LocalDate.of(2026,10,2),LocalTime.of(10,46),LocalTime.of(17,21),LocalTime.of(15,26),LocalTime.of(15,56)),"1392","6.08"),
)
enum class BackfillStatus(val label: String) { READY("Ready to import"), DUPLICATE("Already recorded · skipped"), OVERLAP("Overlapping session · skipped"), SAME_DAY("Existing same-day session · skipped") }
data class BackfillReview(val candidate: BackfillShift, val session: WorkSession, val status: BackfillStatus, val conflicts: List<WorkSession>) {
    val paid: Duration get() = Duration.between(session.clockIn,session.clockOut).minus(if(session.lunchStart==null) Duration.ZERO else Duration.between(session.lunchStart,session.lunchEnd))
}
fun reviewBackfill(candidate: BackfillShift, existing: List<WorkSession>, zone: ZoneId, now: Instant): BackfillReview {
    val next=candidate.input.session(zone,now)
    fun exact(s:WorkSession)=s.state==ShiftState.COMPLETE && s.clockIn==next.clockIn && s.clockOut==next.clockOut && s.lunchStart==next.lunchStart && s.lunchEnd==next.lunchEnd
    fun overlaps(s:WorkSession)=s.clockIn<next.clockOut!! && (s.clockOut==null || s.clockOut>next.clockIn)
    val duplicates=existing.filter(::exact)
    val overlapping=existing.filter(::overlaps)
    val sameDay=existing.filter{it.clockIn.atZone(zone).toLocalDate()==candidate.date}
    val status=when {duplicates.isNotEmpty()->BackfillStatus.DUPLICATE;overlapping.isNotEmpty()->BackfillStatus.OVERLAP;sameDay.isNotEmpty()->BackfillStatus.SAME_DAY;else->BackfillStatus.READY}
    return BackfillReview(candidate,next,status,(duplicates+overlapping+sameDay).distinctBy{it.id})
}
data class BackfillResult(val imported: List<Long>, val skipped: List<BackfillReview>) {
    val message get() = "${imported.size} new shifts imported." + if(skipped.isEmpty()) "" else " ${skipped.size} existing/conflicting records skipped."
}
