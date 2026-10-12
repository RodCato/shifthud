package com.shifthud

import com.shifthud.ui.paystub.*
import com.shifthud.ui.scheduleimport.*
import com.shifthud.domain.scheduleimport.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Representative Passport geometry, not a personal screenshot or production seed. */
class PassportScheduleTest {
    private fun p(text:String,x:Int,y:Int,w:Int=180,h:Int=24)=OcrPiece(text,x,y,x+w,y+h)
    private fun doc(pieces:List<OcrPiece>)=OcrDocument(pieces.map{OcrBlock(it,listOf(OcrLine(it,listOf(it))))})
    private val week=LocalDate.of(2026,10,17)
    private fun fixture(header:String="10/17/2026"):List<OcrPiece> = buildList {
        add(p(header,280,30));add(p("Weekly total: 37 hours",280,90,400))
        val days=listOf("SAT","SUN","MON","TUE","WED","THU","FRI")
        days.forEachIndexed { i,day ->
            val top=180+i*180
            add(p(day,30,top+20,60));add(p("${17+i}",45,top+55,40))
            if(i<5) {
                add(p(when(i){0->"4 PM–11 PM";1->"10 AM–6 PM";else->"8 AM–5 PM"},220,top,320))
                add(p("Grocery",220,top+38));add(p("Store #1234",220,top+72))
                add(p("${if(i==0)6 else if(i==1)7 else 8} net hours",220,top+108))
                add(p(">",700,top+40,16))
            } else if(i==5)add(p("Not scheduled",220,top+20,260))
            // Friday badge is visible, but its body is cropped. No fabricated OFF candidate.
        }
    }
    private fun extract(pieces:List<OcrPiece> = fixture(),week:LocalDate?=null)=extractPassportSchedule(doc(pieces),week)
    @Test fun actualLayoutReconstructsFiveCompleteRowsAndExplicitOff() {
        val r=extract();assertEquals(week,r.week);assertFalse(r.requiresWeekConfirmation)
        val shifts=r.rows.filter{!it.off}
        assertEquals(5,shifts.size);assertEquals(6,r.rows.size);assertEquals(37*60,r.weeklyNetMinutes)
        assertEquals((0L..4L).map{week.plusDays(it)},shifts.map{it.date})
        assertEquals(LocalTime.of(16,0),shifts.first().start)
        assertEquals(listOf(360,420,480,480,480),shifts.map{it.netMinutes})
        assertTrue(shifts.all{it.lunch==60 && it.lunchSource==LunchSource.INFERRED_NET && it.confidence==Confidence.HIGH})
        assertEquals(2220L,shifts.sumOf{Duration.between(it.shift()!!.start,it.shift()!!.end).toMinutes()-it.lunch!!})
        assertEquals(week.plusDays(5),r.rows.single{it.off}.date);assertNull(r.rows.single{it.off}.shift())
        assertTrue(r.rows.none{it.date==week.plusDays(6)})
    }
    @Test fun inputReadingOrderDoesNotAffectAssociations() {val expected=extract();repeat(8){assertEquals(expected,extract(fixture().shuffled(kotlin.random.Random(it))))}}
    @Test fun separateBlocksAndSameBlockProduceSameRows() {
        val p=fixture();val combined=OcrDocument(listOf(OcrBlock(p.first(),p.map{OcrLine(it,listOf(it))})))
        assertEquals(extract(),extractPassportSchedule(combined))
    }
    @Test fun headerIsNotADateOnlyCandidate() {assertFalse(extract().rows.any{!it.off && (it.start==null || it.end==null)})}
    @Test fun weekdaysDerivePositionsNotVisibleRowOrdinal() {
        val pieces=fixture().filter{it.top<180 || it.top>=180+2*180}
        val r=extract(pieces);assertEquals(week.plusDays(2),r.rows.first().date);assertEquals(3,r.rows.count{!it.off})
    }
    @Test fun missingYearRequiresConfirmationThenReconstructsSameGeometry() {
        val pieces=fixture("10/17")
        assertTrue(extract(pieces).requiresWeekConfirmation);assertTrue(extract(pieces).rows.all{it.date==null})
        assertEquals(week,extract(pieces,week).rows.first().date)
    }
    @Test fun nonSaturdayHeaderDoesNotGuessWeek() {val r=extract(fixture("10/18/2026"));assertNull(r.week);assertTrue(r.requiresWeekConfirmation)}
    @Test fun invalidCalendarHeaderDoesNotNormalize() {assertNull(extract(fixture("02/30/2026")).week)}
    @Test fun badgeMismatchStaysUnresolved() {
        val p=fixture().map{if(it.text=="17")it.copy(text="18") else it}
        val c=extract(p).rows.first();assertNull(c.date);assertEquals(Confidence.UNKNOWN,c.confidence);assertTrue(c.warnings.any{it.contains("disagrees")})
    }
    @Test fun totalAloneNeverCreatesShiftOrProvesCompleteness() {
        val r=extract(listOf(p("10/17/2026",280,30),p("Weekly total: 37 hours",280,90,400)))
        assertTrue(r.rows.isEmpty())
    }
    @Test fun orphanBodyNeverGetsAssignedAnArbitraryDate() {
        val p=fixture().filterNot{it.left<100 && it.top in 350..500}
        val r=extract(p);assertFalse(r.rows.any{it.start==LocalTime.of(10,0)})
    }
    @Test fun duplicatesAndConflictsStillUseExistingProtection() {
        val s=extract().rows.first().shift()!!
        assertEquals(ImportKind.EXACT_DUPLICATE,classify(s,listOf(s.copy(id=3))).kind)
        assertEquals(ImportKind.POSSIBLE_UPDATE,classify(s,listOf(s.copy(id=3,scheduledStart=LocalTime.of(15,0)))).kind)
    }
    @Test fun scaledCoordinatesKeepAssociations() {
        val p=fixture().map{it.copy(left=it.left*2,top=it.top*2,right=it.right*2,bottom=it.bottom*2)}
        assertEquals(extract().rows.map{it.shift()},extract(p).rows.map{it.shift()})
    }
    @Test fun visibleFridayUsesSeventhWeekdayPositionAcrossYearBoundary() {
        val december=LocalDate.of(2026,12,26)
        val pieces=fixture("12/26/2026").map { piece ->
            if(piece.left<100 && piece.text.toIntOrNull() in 17..23)piece.copy(text=december.plusDays((piece.text.toInt()-17).toLong()).dayOfMonth.toString()) else piece
        }+listOf(p("8 AM–5 PM",220,1260,320),p("8 net hours",220,1368))
        val r=extract(pieces)
        assertEquals(LocalDate.of(2027,1,1),r.rows.last().date);assertEquals(6,r.rows.count{!it.off})
    }
    @Test fun validatedDayOffDoesNotHaveSpuriousWarnings() {
        val off=extract().rows.single{it.off};assertTrue(off.warnings.isEmpty());assertEquals(Confidence.HIGH,off.confidence)
    }

    @Test fun numericMonthDayBadgeIsValidated() {
        val good=fixture().map{if(it.text=="17")it.copy(text="10/17") else it}
        val bad=fixture().map{if(it.text=="17")it.copy(text="10/18") else it}
        assertEquals(week,extract(good).rows.first().date);assertNull(extract(bad).rows.first().date)
    }

}
