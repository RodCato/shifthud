package com.shifthud.ui.scheduleimport

import com.shifthud.ui.paystub.OcrPiece
import com.shifthud.ui.paystub.ocrRows

/** Preserve aligned list rows, or read separately bounded weekday columns top-to-bottom. */
fun scheduleOcrRows(pieces:List<OcrPiece>):String {
    val day=Regex("\\b(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)\\b",RegexOption.IGNORE_CASE)
    val anchors=pieces.filter{day.containsMatchIn(it.text)}.sortedBy{it.top}
    val first=anchors.firstOrNull()?:return ocrRows(pieces)
    val band=anchors.filter{kotlin.math.abs(it.top-first.top)<maxOf(first.bottom-first.top,1)}.sortedBy{it.left}
    if(band.size<2)return ocrRows(pieces)
    val centers=band.map{(it.left+it.right)/2}
    val edges=centers.zipWithNext().map{(a,b)->(a+b)/2}
    val header=ocrRows(pieces.filter{it.bottom<=first.top})
    val columns=band.indices.map { column ->
        val left=edges.getOrElse(column-1){Int.MIN_VALUE};val right=edges.getOrElse(column){Int.MAX_VALUE}
        ocrRows(pieces.filter { p->p.bottom>first.top && (p.left+p.right)/2 in left until right })
    }
    // Mark the association for explicit review; geometry is helpful, not a confidence score from ML Kit.
    return header+"\n"+columns.joinToString("\n")+"\n[Column layout: verify date/time associations]"
}
