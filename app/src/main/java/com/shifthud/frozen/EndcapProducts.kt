package com.shifthud.frozen

import com.shifthud.ui.paystub.OcrDocument
import java.util.Locale

data class EndcapProduct(val name: String, val sku: String = "")
data class EndcapPage(val location: String?, val products: List<EndcapProduct>, val warning: String)
fun normalizeEndcap(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
fun sameEndcapProduct(a: EndcapProduct,b: EndcapProduct): Boolean =
    if(a.sku.isNotBlank() && b.sku.isNotBlank()) normalizeEndcap(a.sku)==normalizeEndcap(b.sku)
    else normalizeEndcap(a.name)==normalizeEndcap(b.name)
fun distinctEndcapProducts(rows: List<EndcapProduct>): List<EndcapProduct> = rows.sortedBy { it.sku.isBlank() }.fold(emptyList()) { result,row ->
    require(row.name.isNotBlank()) { "Product names cannot be blank." }
    if(result.any { sameEndcapProduct(it,row) }) result else result+row
}

/** Geometry selects description/SKU columns when available; all other columns are ignored. */
fun parseEndcapPage(document: OcrDocument): EndcapPage {
    val rows=mutableListOf<MutableList<com.shifthud.ui.paystub.OcrPiece>>()
    document.elements.sortedWith(compareBy({it.top},{it.left})).forEach { piece ->
        val row=rows.lastOrNull();val first=row?.firstOrNull()
        if(first!=null && kotlin.math.abs((piece.top+piece.bottom)-(first.top+first.bottom))/2.0 < minOf(piece.bottom-piece.top,first.bottom-first.top)*0.45) row+=piece
        else rows+=mutableListOf(piece)
    }
    var location: String?=null
    var target=false
    var descriptionLeft:Int?=null
    var descriptionRight=Int.MAX_VALUE
    var skuLeft:Int?=null
    var skuRight=Int.MAX_VALUE
    val products=mutableListOf<EndcapProduct>()
    rows.forEach { unsorted ->
        val pieces=unsorted.sortedBy { it.left }
        val line=pieces.joinToString(" ") { it.text }
        if(Regex("(?i)\\b(aisle|endcap|display location)\\b").containsMatchIn(line)) {
            target=Regex("(?i)aisle\\s*10\\s*front\\s*[-—–:]?\\s*center").containsMatchIn(line)
            descriptionLeft=null;skuLeft=null;descriptionRight=Int.MAX_VALUE;skuRight=Int.MAX_VALUE
            if(target) location="Aisle 10 Front — Center"
            return@forEach
        }
        if(!target) return@forEach
        val header=pieces.indexOfFirst { Regex("(?i)^(product\\s+)?description$").matches(it.text.trim()) }
        if(header>=0) {
            descriptionLeft=pieces[header].left
            descriptionRight=pieces.drop(header+1).firstOrNull()?.left ?: Int.MAX_VALUE
            val sku=pieces.indexOfFirst { Regex("(?i)^(item|sku|item number|item #)$").matches(it.text.trim()) }
            if(sku>=0) { skuLeft=pieces[sku].left;skuRight=pieces.drop(sku+1).firstOrNull()?.left ?: Int.MAX_VALUE }
            return@forEach
        }
        if(Regex("(?i)\\b(description|item number|sku|initial|interim|forecast|total|store display|page|price|promotion|week ending)\\b").containsMatchIn(line)) return@forEach
        if(descriptionLeft!=null) {
            val name=pieces.filter { it.left>=descriptionLeft!!-12 && it.left<descriptionRight-12 }.joinToString(" ") { it.text }.trim()
            val sku=if(skuLeft==null) "" else pieces.filter { it.left>=skuLeft!!-12 && it.left<skuRight-12 }.joinToString("") { it.text }.takeIf { it.matches(Regex("\\d{4,12}")) }.orEmpty()
            if(name.count { it.isLetter() }>=3) products+=EndcapProduct(name,sku)
        } else {
            val match=Regex("^\\s*(\\d{4,12})\\s+(.+)$").find(line)
            val name=(match?.groupValues?.get(2) ?: line).replace(Regex("\\s+(?:\\$?\\d[\\d.,]*\\s*)+$"), "").trim()
            if(name.count { it.isLetter() }>=3) products+=EndcapProduct(name,match?.groupValues?.get(1).orEmpty())
        }
    }
    return EndcapPage(location,distinctEndcapProducts(products),
        if(location==null) "Display location not identified. Confirm the location and add products manually, or rotate/retry this page."
        else "Check every product against the image. Cropped or unrecognized rows may be missing.")
}
