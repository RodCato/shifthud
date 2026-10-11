package com.shifthud.ui.scheduleimport

import com.shifthud.domain.scheduleimport.*
import com.shifthud.ui.paystub.*
import java.time.*

private val badgeDay=Regex("^(sat(?:urday)?|sun(?:day)?|mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?)[,. ]*(\\d{1,2})?$",RegexOption.IGNORE_CASE)
private val days=listOf("sat","sun","mon","tue","wed","thu","fri")
private val clockToken=Regex("\\b\\d{1,2}(?::\\d{2})?\\s*[ap](?:\\.?m\\.?)?\\b|\\b\\d{1,2}:\\d{2}\\b",RegexOption.IGNORE_CASE)
private val offText=Regex("\\b(?:not scheduled|day off|off)\\b",RegexOption.IGNORE_CASE)
private val totalText=Regex("\\b(?:total|weekly)\\b",RegexOption.IGNORE_CASE)
private fun OcrPiece.cy()=(top+bottom)/2.0
private fun OcrPiece.cx()=(left+right)/2.0

/** Join fragments on a visual baseline, retaining the resulting bounding rectangle. */
private fun visualLines(pieces:List<OcrPiece>):List<OcrPiece> {
    val rows=mutableListOf<MutableList<OcrPiece>>()
    pieces.distinct().sortedWith(compareBy<OcrPiece>{it.top}.thenBy{it.left}).forEach { p ->
        val row=rows.lastOrNull()
        val first=row?.firstOrNull()
        if(first!=null && kotlin.math.abs(p.cy()-first.cy()) < minOf(p.bottom-p.top,first.bottom-first.top)*0.55)row+=p
        else rows+=mutableListOf(p)
    }
    return rows.map { row -> OcrPiece(row.sortedBy{it.left}.joinToString(" "){it.text},row.minOf{it.left},row.minOf{it.top},row.maxOf{it.right},row.maxOf{it.bottom}) }
}

/**
 * Passport is a vertical list of left-hand weekday/date badges and right-hand shift bodies.
 * Geometry is kept until badge/body association is complete. Never derive dates from row index.
 * Other layouts continue through the established parser, with its existing review requirements.
 */
fun extractPassportSchedule(document:OcrDocument, confirmedWeek:LocalDate?=null, defaultLunch:Int=60):ScheduleExtraction {
    require(confirmedWeek==null || confirmedWeek.dayOfWeek==DayOfWeek.SATURDAY)
    val pieces=document.elements.distinct()
    val width=pieces.maxOfOrNull{it.right}?:0
    val badges=pieces.filter{badgeDay.matches(it.text.trim()) && it.right<width*0.5}
        .sortedBy{it.top}
    // Horizontal weekday calendars and full textual date rows retain the existing fallback.
    if(badges.isEmpty() || badges.zipWithNext().any{(a,b)->kotlin.math.abs(a.cy()-b.cy())<maxOf(a.bottom-a.top,b.bottom-b.top)})
        return extractSchedule(scheduleOcrRows(document.lines.map{it.bounds}),confirmedWeek,defaultLunch)
    val leftEdge=badges.maxOf{it.right}
    val body=visualLines(pieces.filter{it.left>leftEdge})
    val firstRowTop=minOf(badges.first().top,body.firstOrNull{clockToken.containsMatchIn(it.text) || offText.containsMatchIn(it.text)}?.top?:badges.first().top)
    val header=visualLines(pieces.filter{it.bottom<firstRowTop})
    val dates=header.flatMap { line -> Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b").findAll(line.text).map { m ->
        runCatching{LocalDate.of(m.groupValues[3].toInt(),m.groupValues[1].toInt(),m.groupValues[2].toInt())}.getOrNull()
    }.toList() }
    val badgeNumbers=badges.any { badge -> badgeDay.matchEntire(badge.text.trim())!!.groupValues[2].isNotEmpty() || pieces.any {
        it.right<=leftEdge && Regex("\\d{1,2}(?:/\\d{1,2}(?:/\\d{4})?)?").matches(it.text.trim()) && it.cy()>=badge.bottom && it.cy()<badge.bottom+(badge.bottom-badge.top)*2.5
    } }
    if(dates.isEmpty() && !badgeNumbers)return extractSchedule(scheduleOcrRows(document.lines.map{it.bounds}),confirmedWeek,defaultLunch)
    val recognized=dates.singleOrNull()?.takeIf{it.dayOfWeek==DayOfWeek.SATURDAY}
    val fallback=extractSchedule(ocrRows(header),defaultLunch=defaultLunch)
    val inferred=recognized?:fallback.week?.takeUnless{fallback.requiresWeekConfirmation}
    val week=confirmedWeek?:inferred
    val warnings=mutableListOf<String>()
    if(week==null)warnings+="Confirm the screenshot's Saturday and year; the week header is missing or ambiguous."
    if(confirmedWeek!=null && inferred!=null && confirmedWeek!=inferred)warnings+="Selected week differs from the recognized header. Review date badges."

    // Start/end text (or explicit OFF) anchors each body. Job/store/net lines follow it.
    // Match each badge to the nearest start anchor, then use successive body starts as row boundaries.
    val starts=body.filter{!totalText.containsMatchIn(it.text) && (clockToken.containsMatchIn(it.text) || offText.containsMatchIn(it.text))}
    val used=mutableSetOf<OcrPiece>()
    val matched=badges.mapIndexed { index,badge ->
        val gap=listOfNotNull(badges.getOrNull(index-1)?.let{badge.cy()-it.cy()},badges.getOrNull(index+1)?.let{it.cy()-badge.cy()}).minOrNull()
            ?:maxOf((badge.bottom-badge.top)*8.0,120.0)
        val nearby=starts.filter{kotlin.math.abs(it.cy()-badge.cy())<gap*0.55}.sortedBy{kotlin.math.abs(it.cy()-badge.cy())}
        val primary=nearby.firstOrNull()?.takeIf { p ->
            nearby.getOrNull(1)?.let{q->kotlin.math.abs(q.cy()-badge.cy())-kotlin.math.abs(p.cy()-badge.cy()) > (badge.bottom-badge.top)*0.5}?:true
        }
        badge to primary?.takeIf{used.add(it)}
    }
    val rows=matched.mapNotNull { (badge,start) ->
        if(start==null) { warnings+="A cropped or ambiguous ${badge.text} row was not imported. Review the original image.";return@mapNotNull null }
        val index=badges.indexOf(badge)
        val nextStart=starts.firstOrNull{it.top>start.top}
        val nextBadge=badges.getOrNull(index+1)
        // An unmatched next badge still ends the current row; never absorb its unrelated text.
        val end=minOf(nextStart?.top?.toDouble()?:Double.MAX_VALUE,nextBadge?.let{it.top.toDouble()}?:Double.MAX_VALUE)
        val rowBody=body.filter{it.top>=start.top && it.cy()<end && !totalText.containsMatchIn(it.text)}
        val offset=days.indexOf(badgeDay.matchEntire(badge.text.trim())!!.groupValues[1].take(3).lowercase())
        val derived=week?.plusDays(offset.toLong())
        val badgePieces=pieces.filter{it.right<=leftEdge+maxOf(10,badge.right-badge.left)/2 &&
            kotlin.math.abs(it.cx()-badge.cx())<maxOf(badge.right-badge.left,30) &&
            it.cy()>=badge.top && it.cy()<minOf(end,badge.bottom+(badge.bottom-badge.top)*2.5)}
        val printedNumbers=(badgeDay.matchEntire(badge.text.trim())!!.groupValues[2].toIntOrNull()?.let{listOf(it)}.orEmpty()+
            badgePieces.mapNotNull{it.text.trim().takeIf{t->Regex("\\d{1,2}").matches(t)}?.toIntOrNull()}).distinct()
        val printedDates=badgePieces.mapNotNull { Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?$").matchEntire(it.text.trim()) }
        val dateDisagrees=derived!=null && printedDates.any { m ->
            m.groupValues[1].toInt()!=derived.monthValue || m.groupValues[2].toInt()!=derived.dayOfMonth ||
                (m.groupValues[3].isNotEmpty() && m.groupValues[3].toInt()!=derived.year)
        }
        val issues=mutableListOf<String>()
        val validDate=when {
            dateDisagrees->{issues+="Date badge disagrees with the workweek; correct the date.";null}
            printedNumbers.size>1->{issues+="Ambiguous date badge; confirm the date.";null}
            derived!=null && printedNumbers.singleOrNull()?.let{it!=derived.dayOfMonth}==true->{issues+="Date badge disagrees with the workweek; correct the date.";null}
            else->derived
        }
        val raw=ocrRows((badgePieces+badge+rowBody).distinct())
        // Only after reconstruction do we reuse the established time/net/lunch validation.
        val rowText="${days[offset]} ${validDate?:""}\n"+rowBody.joinToString("\n"){it.text}
        val parsed=extractSchedule(rowText,week,defaultLunch).rows.singleOrNull()?:return@mapNotNull null
        parsed.copy(raw=raw,date=validDate,weekday=DayOfWeek.of((offset+5)%7+1),warnings=parsed.warnings+issues)
    }
    val totals=visualLines(pieces).filter{totalText.containsMatchIn(it.text)}
    val net=totals.mapNotNull{extractSchedule(it.text).weeklyNetMinutes}.distinct().singleOrNull()
    if(rows.isEmpty())warnings+="No complete shift rows recognized. Review a clearer screenshot."
    return ScheduleExtraction(week,week==null,rows,net,warnings.distinct())
}
