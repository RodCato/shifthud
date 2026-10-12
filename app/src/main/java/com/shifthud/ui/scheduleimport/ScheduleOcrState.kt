package com.shifthud.ui.scheduleimport

import com.shifthud.ui.paystub.*
import org.json.*

/** Saved review state contains bounded recognized text/geometry, never image bytes or URIs. */
fun OcrDocument.encodeGeometry():String {
    fun OcrPiece.json()=JSONArray(listOf(text,left,top,right,bottom))
    return JSONArray().apply { blocks.forEach { b->put(JSONObject().apply {
        put("box",b.bounds.json());put("lines",JSONArray().apply { b.lines.forEach { l->put(JSONObject().apply {
            put("box",l.bounds.json());put("elements",JSONArray().apply{l.elements.forEach{put(it.json())}})
        }) } })
    }) } }.toString().also{require(it.length<=200000){"Too much recognized text. Choose a cropped schedule image."}}
}
fun decodeScheduleGeometry(value:String):OcrDocument? = runCatching {
    fun JSONArray.piece()=OcrPiece(getString(0),getInt(1),getInt(2),getInt(3),getInt(4))
    require(value.length<=200000)
    val blocks=JSONArray(value)
    OcrDocument((0 until blocks.length()).map { i -> val b=blocks.getJSONObject(i);val lines=b.getJSONArray("lines")
        OcrBlock(b.getJSONArray("box").piece(),(0 until lines.length()).map { j->val l=lines.getJSONObject(j);val elements=l.getJSONArray("elements")
            OcrLine(l.getJSONArray("box").piece(),(0 until elements.length()).map{elements.getJSONArray(it).piece()})
        })
    })
}.getOrNull()
