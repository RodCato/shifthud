package com.shifthud

import com.shifthud.domain.importing.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class BackfillTest {
    private val zone=ZoneId.of("America/New_York")
    private val now=Instant.parse("2026-10-10T18:00:00Z")
    private fun review(i:Int=0,existing:List<WorkSession> = emptyList())=reviewBackfill(PUBLIX_BACKFILL[i],existing,zone,now)
    @Test fun sixExactDatesAndStores(){assertEquals(listOf("2026-09-25","2026-09-26","2026-09-28","2026-09-29","2026-09-30","2026-10-02"),PUBLIX_BACKFILL.map{it.date.toString()});assertEquals(listOf("1425","1425","1425","1425","1425","1392"),PUBLIX_BACKFILL.map{it.store})}
    @Test fun punchesAndLunchesMatchMinutePrecisionSource(){
        val expected=listOf(listOf("12:13","14:22",null,null),listOf("08:00","13:00",null,null),listOf("04:00","13:00","09:54","10:49"),listOf("08:00","17:01","11:23","12:16"),listOf("04:00","13:00","09:55","10:50"),listOf("10:46","17:21","15:26","15:56"))
        PUBLIX_BACKFILL.forEachIndexed{i,c->val s=review(i).session;val values=listOf(s.clockIn,s.clockOut,s.lunchStart,s.lunchEnd);assertEquals(expected[i],values.map{it?.atZone(zone)?.toLocalTime()?.toString()});values.filterNotNull().forEach{assertEquals(c.date,it.atZone(zone).toLocalDate())};assertTrue(s.manuallyEntered);assertEquals(ShiftState.COMPLETE,s.state)}
    }
    @Test fun chosenWorkZoneControlsInstants(){val candidate=PUBLIX_BACKFILL.first();assertEquals(Instant.parse("2026-09-25T16:13:00Z"),review().session.clockIn);assertEquals(Instant.parse("2026-09-25T17:13:00Z"),reviewBackfill(candidate,emptyList(),ZoneId.of("America/Chicago"),now).session.clockIn)}
    @Test fun paidTimeUsesLunchPunchesNotReportedDecimals(){assertEquals(listOf(129L,300L,485L,488L,485L,365L),PUBLIX_BACKFILL.indices.map{review(it).paid.toMinutes()});assertEquals(2123L,PUBLIX_BACKFILL.indices.drop(1).sumOf{review(it).paid.toMinutes()});assertEquals("8.09",PUBLIX_BACKFILL[4].employerHours);assertEquals("8.08",PUBLIX_BACKFILL[2].employerHours);assertEquals(review(2).paid,review(4).paid)}
    @Test fun saturdayFridayAttribution(){assertEquals(LocalDate.of(2026,9,19),workWeekFor(PUBLIX_BACKFILL.first().date).start);PUBLIX_BACKFILL.drop(1).forEach{assertEquals(LocalDate.of(2026,9,26),workWeekFor(it.date).start);assertEquals(LocalDate.of(2026,10,2),workWeekFor(it.date).endInclusive)}}
    @Test fun exactDuplicateIgnoresUnrelatedMetadata(){val old=review().session.copy(id=9,manuallyEntered=false,correctionRevision=3);val r=review(existing=listOf(old));assertEquals(BackfillStatus.DUPLICATE,r.status);assertEquals(listOf(old),r.conflicts)}
    @Test fun changedLunchIsOverlapNotDuplicate(){val old=review(2).session.copy(id=4,lunchEnd=review(2).session.lunchEnd!!.minusSeconds(60));assertEquals(BackfillStatus.OVERLAP,review(2,listOf(old)).status)}
    @Test fun distinctSameDayShiftIsConflict(){val first=review().session;val old=first.copy(id=5,clockIn=first.clockIn.minusSeconds(7200),clockOut=first.clockIn.minusSeconds(3600));assertEquals(BackfillStatus.SAME_DAY,review(existing=listOf(old)).status)}
    @Test fun priorDayOverlapAndOpenSessionAreConflicts(){val s=review().session;val previous=s.copy(id=6,clockIn=s.clockIn.minusSeconds(86400));assertEquals(BackfillStatus.OVERLAP,review(existing=listOf(previous)).status);val active=WorkSession(id=7,clockIn=s.clockIn.minusSeconds(86400));assertEquals(BackfillStatus.OVERLAP,review(existing=listOf(active)).status)}
    @Test fun allConflictRecordsRemainVisible(){val s=review().session;val old=s.copy(id=3);val second=s.copy(id=4,clockIn=s.clockIn.minusSeconds(60));assertEquals(listOf(3L,4L),review(existing=listOf(old,second)).conflicts.map{it.id})}
    @Test fun noConflictAcrossSeparateDays(){assertEquals(BackfillStatus.READY,review(existing=listOf(review(1).session.copy(id=1))).status)}
    @Test fun futureValidationIsNotBypassed(){assertThrows(IllegalArgumentException::class.java){reviewBackfill(PUBLIX_BACKFILL.first(),emptyList(),zone,Instant.parse("2026-09-01T00:00:00Z"))}}
}
