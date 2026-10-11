package com.shifthud.domain.scheduleimport

import com.shifthud.domain.model.ScheduledShift
import java.time.*
import java.math.BigDecimal

enum class Confidence { HIGH, REVIEW_REQUIRED, UNKNOWN }
enum class LunchSource { EXPLICIT, INFERRED_NET, DEFAULT, CORRECTED }
data class ScheduleCandidate(
    val raw: String, val date: LocalDate?, val start: LocalTime?, val end: LocalTime?,
    val lunch: Int?, val lunchSource: LunchSource, val netMinutes: Int? = null,
    val weekday: DayOfWeek? = null, val off: Boolean = false, val warnings: List<String> = emptyList(),
) {
    val confidence: Confidence get() = if(date==null || (!off && (start==null || end==null))) Confidence.UNKNOWN else if(warnings.isNotEmpty()) Confidence.REVIEW_REQUIRED else Confidence.HIGH
    /** Persist employer evidence separately; the weekly plan is never an OCR field. */
    fun shift(): ScheduledShift? {
        if(off || date==null || start==null || end==null) return null
        val span=Duration.between(start,end).toMinutes().let { if(it<=0) it+1440 else it }
        val employerLunch=lunch.takeUnless { lunchSource==LunchSource.DEFAULT }
        if(netMinutes!=null && (netMinutes<0 || netMinutes>span)) return null
        if(employerLunch!=null && (employerLunch<0 || employerLunch>=span)) return null
        return ScheduledShift(date=date, scheduledStart=start, scheduledEnd=end,
            employerNetMinutes=netMinutes, employerLunchMinutes=employerLunch)
    }

}
data class ScheduleExtraction(val week: LocalDate?, val requiresWeekConfirmation: Boolean, val rows: List<ScheduleCandidate>, val weeklyNetMinutes: Int?, val warnings: List<String>)
private val monthNames=listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec")
private val weekdayNames=listOf("mon","tue","wed","thu","fri","sat","sun")
private val weekdayRegex=Regex("\\b(mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)\\b",RegexOption.IGNORE_CASE)
private val dateRegex=Regex("\\b(?:(\\d{4})-(\\d{1,2})-(\\d{1,2})|([A-Za-z]{3,9})\\.?\\s+(\\d{1,2})(?:,?\\s+(20\\d{2}))?|(\\d{1,2})/(\\d{1,2})(?:/(20\\d{2}))?)\\b")
private data class DateParts(val month:Int,val day:Int,val year:Int?)
private fun parts(text:String):List<DateParts> = dateRegex.findAll(text).mapNotNull { m ->
    val g=m.groupValues
    when {
        g[1].isNotEmpty()->DateParts(g[2].toInt(),g[3].toInt(),g[1].toInt())
        g[4].isNotEmpty()->monthNames.indexOf(g[4].take(3).lowercase()).takeIf{it>=0}?.let { DateParts(it+1,g[5].toInt(),g[6].toIntOrNull()) }
        else->DateParts(g[7].toInt(),g[8].toInt(),g[9].toIntOrNull())
    }
}.toList()
private val timeToken="(?:\\d{1,2}(?::\\d{2})?\\s*[aApP](?:\\.?[mM]\\.?)?|\\d{1,2}:\\d{2})"
private val timeRange=Regex("(?<![\\d:])($timeToken)\\s*[-–—]\\s*($timeToken)(?![\\d:])")
fun parseScheduleTime(value:String):LocalTime? = runCatching {
    val text=value.trim().lowercase().replace(".","").replace(" ","")
    val m=Regex("(\\d{1,2})(?::(\\d{2}))?([ap])m?").matchEntire(text)
    if(m!=null) {
        val h=m.groupValues[1].toInt(); require(h in 1..12)
        LocalTime.of(h%12+if(m.groupValues[3]=="p")12 else 0,m.groupValues[2].toIntOrNull()?:0)
    } else {
        // A leading zero or hour > 12 is unambiguously 24-hour. 4:00 needs review.
        require(Regex("\\d{1,2}:\\d{2}").matches(text))
        val (h,mn)=text.split(':').map{it.toInt()};require(text.startsWith("0") || h>12)
        LocalTime.of(h,mn)
    }
}.getOrNull()
fun decimalHoursMinutes(text:String):Int? = runCatching { BigDecimal(text).multiply(BigDecimal(60)).intValueExact().also{require(it>=0)} }.getOrNull()
private fun paidMinutes(text:String):Int? {
    val explicit=Regex("(?:net|paid)(?:\\s+hours)?\\s*:?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:h|hrs?|hours?)?",RegexOption.IGNORE_CASE).find(text)
    val trailing=Regex("(?<![\\d:])(\\d+(?:\\.\\d+)?)\\s*(?:(?:net|paid)\\s+)?(?:h|hrs?|hours?)\\b",RegexOption.IGNORE_CASE).find(text)
    val match=explicit?:trailing?:return null
    val base=decimalHoursMinutes(match.groupValues[1])?:return null
    val minutes=Regex("^\\s*(?:h|hrs?|hours?)?\\s*(\\d{1,2})\\s*(?:m|min|minutes?)\\b",RegexOption.IGNORE_CASE)
        .find(text.substring(match.range.last+1))?.groupValues?.get(1)?.toInt()?:0
    return (base+minutes).takeIf{minutes<60}
}
/** Geometry is normalized to reading rows before parsing. No date uses today's year. */
fun extractSchedule(text:String, confirmedWeek:LocalDate?=null, defaultLunch:Int=60):ScheduleExtraction {
    require(confirmedWeek==null || confirmedWeek.dayOfWeek==DayOfWeek.SATURDAY)
    val lines=text.take(20000).lines().map{it.trim()}.filter{it.isNotEmpty()}
    val headers=lines.filter { weekdayRegex.find(it)==null && timeRange.find(it)==null }
    val header= headers.firstOrNull { parts(it).isNotEmpty() && (Regex("[-–—]|week",RegexOption.IGNORE_CASE).containsMatchIn(it) || Regex("\\d{1,2}/\\d{1,2}/\\d{4}").matches(it)) }
    val hp=header?.let(::parts).orEmpty()
    val year=header?.let{Regex("\\b20\\d{2}\\b").findAll(it).lastOrNull()?.value?.toInt()}
    val inferred=hp.firstOrNull()?.let { p -> runCatching {
        // A lone trailing year in a Dec–Jan range belongs to January.
        val y=p.year?:year?:error("Missing year")
        val startYear=if(p.year==null && p.month==12 && hp.lastOrNull()?.month==1) y-1 else y
        LocalDate.of(startYear,p.month,p.day)
    }.getOrNull() }?.takeIf{it.dayOfWeek==DayOfWeek.SATURDAY}
    val printedEnd=if(inferred==null) null else runCatching {
        if(hp.size>1) {
            val p=hp.last();LocalDate.of(p.year?:inferred.plusDays(6).year,p.month,p.day)
        } else {
            val day=Regex("[-–—]\\s*(\\d{1,2})(?:,?\\s+20\\d{2})?\\s*$").find(header.orEmpty())?.groupValues?.get(1)?.toInt()?:error("Missing end")
            inferred.plusDays(6).also{require(it.dayOfMonth==day)}
        }
    }.getOrNull()
    val completeRange=inferred!=null && (printedEnd==inferred.plusDays(6) || Regex("\\d{1,2}/\\d{1,2}/\\d{4}").matches(header.orEmpty()))
    val week=confirmedWeek?:inferred
    val warnings=mutableListOf<String>()
    if(confirmedWeek==null && !completeRange)warnings+="Confirm the screenshot's Saturday and year. Do not assume the current week."
    if(confirmedWeek!=null && inferred!=null && confirmedWeek!=inferred)warnings+="Selected week differs from the recognized heading. Review every date."
    val groups=mutableListOf<StringBuilder>()
    lines.forEach { line ->
        val isRow=weekdayRegex.containsMatchIn(line) || (parts(line).isNotEmpty() && line!=header && (timeRange.containsMatchIn(line) || !Regex("week|total",RegexOption.IGNORE_CASE).containsMatchIn(line)))
        if(isRow && groups.lastOrNull()?.toString()?.let { parts(it).isEmpty() && timeRange.find(it)==null && weekdayRegex.containsMatchIn(it) }==true && weekdayRegex.find(line)==null)groups.last().append(" ").append(line)
        else if(isRow)groups+=StringBuilder(line)
        else if(groups.isNotEmpty() && !Regex("weekly|total",RegexOption.IGNORE_CASE).containsMatchIn(line))groups.last().append("\n").append(line)
    }
    val rows=groups.take(50).map { group ->
        val raw=group.toString(); val issues=mutableListOf<String>()
        if(text.contains("[Column layout:"))issues+="Verify the recognized column association."
        val ds=parts(raw);val p=ds.firstOrNull()
        val wd=weekdayRegex.find(raw)?.value?.take(3)?.lowercase()?.let{DayOfWeek.of(weekdayNames.indexOf(it)+1)}
        val date= if(p!=null) {
            if(p.year!=null)runCatching{LocalDate.of(p.year,p.month,p.day)}.getOrNull()
            else week?.let { w -> (0L..6L).map{w.plusDays(it)}.singleOrNull{it.monthValue==p.month && it.dayOfMonth==p.day} }
        } else null // A weekday without a printed calendar date stays unknown.
        if(date!=null && wd!=null && date.dayOfWeek!=wd)issues+="Printed weekday does not match date. Correct and acknowledge."
        if(ds.size>1)issues+="Multiple dates in one row; verify column association."
        if(date==null)issues+="Unknown date; choose the printed date after confirming the week."
        val ranges=timeRange.findAll(raw).toList()
        if(ranges.size>1)issues+="Multiple time ranges; select the correct start and end."
        val range=ranges.firstOrNull()
        val tokens=Regex("(?<![\\d:])$timeToken(?![\\d:])").findAll(raw).toList()
        if(range==null && tokens.size>2)issues+="Multiple time values; verify start and end columns."
        val start=(range?.groupValues?.get(1)?:tokens.getOrNull(0)?.value)?.let(::parseScheduleTime)
        val end=(range?.groupValues?.get(2)?:tokens.getOrNull(1)?.value)?.let(::parseScheduleTime)
        val off=Regex("\\b(?:off|not scheduled|day off)\\b",RegexOption.IGNORE_CASE).containsMatchIn(raw) && range==null
        if(!off && (start==null || end==null))issues+="Unknown or ambiguous time; explicitly choose AM/PM."
        val unlabeled=(range?:tokens.lastOrNull())?.let{raw.substring(it.range.last+1).trim().takeIf{v->Regex("\\d+(?:\\.\\d+)?").matches(v)}}?.let(::decimalHoursMinutes)
        val net=paidMinutes(raw)?:unlabeled
        if(unlabeled!=null)issues+="Unlabeled hours associated by row; confirm these are paid/net hours."
        val explicit=Regex("(?:lunch|break)\\s*:?\\s*(\\d+)\\s*(?:min|minutes?)\\b",RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val span=if(start!=null && end!=null)Duration.between(start,end).toMinutes().let{if(it<=0)it+1440 else it} else null
        val inferredLunch=if(net!=null && span!=null)(span-net).toInt() else null
        val source=if(explicit!=null)LunchSource.EXPLICIT else if(net!=null)LunchSource.INFERRED_NET else LunchSource.DEFAULT
        val lunch=explicit?:if(net!=null)inferredLunch else defaultLunch
        if(lunch==null || lunch !in 0..240)issues+="Lunch could not be inferred safely; review paid hours and lunch."
        if(explicit!=null && inferredLunch!=null && explicit!=inferredLunch)issues+="Explicit lunch and net hours disagree."

        ScheduleCandidate(raw,date,start,end,lunch?.takeIf{it in 0..240},source,net,wd,off,issues)
    }
    val weekly=lines.firstOrNull{Regex("weekly|total",RegexOption.IGNORE_CASE).containsMatchIn(it)}?.let(::paidMinutes)
    if(rows.isEmpty())warnings+="No dated shifts recognized. Try a clearer image or add review rows manually."
    return ScheduleExtraction(week,confirmedWeek==null && !completeRange,rows,weekly,warnings)
}

enum class ImportKind { NEW, EXACT_DUPLICATE, POSSIBLE_UPDATE, CONFLICT, INVALID }
data class CandidateMatch(val kind:ImportKind,val existing:List<ScheduledShift>)
fun overlaps(a:ScheduledShift,b:ScheduledShift)=a.start<b.end && b.start<a.end
fun classify(candidate:ScheduledShift?, existing:List<ScheduledShift>):CandidateMatch {
    if(candidate==null)return CandidateMatch(ImportKind.INVALID,emptyList())
    val same=existing.filter{it.date==candidate.date && it.scheduledStart==candidate.scheduledStart && it.scheduledEnd==candidate.scheduledEnd}
    // Reimport never overwrites a user's existing lunch override.
    if(same.isNotEmpty())return CandidateMatch(ImportKind.EXACT_DUPLICATE,same)
    val touching=existing.filter{overlaps(candidate,it)}
    val onDate=existing.filter{it.date==candidate.date}
    return when {
        touching.size>1 || touching.any{it.date!=candidate.date}->CandidateMatch(ImportKind.CONFLICT,touching)
        onDate.size==1->CandidateMatch(ImportKind.POSSIBLE_UPDATE,onDate)
        touching.isNotEmpty()->CandidateMatch(ImportKind.CONFLICT,touching)
        else->CandidateMatch(ImportKind.NEW,emptyList())
    }
}
data class ScheduleImportChoice(val shift:ScheduledShift,val replacing:ScheduledShift?=null)
data class ScheduleImportSummary(val imported:Int,val updated:Int,val duplicates:Int,val conflicts:Int=0,val failed:Int=0,val refreshDeferred:Boolean=false)
