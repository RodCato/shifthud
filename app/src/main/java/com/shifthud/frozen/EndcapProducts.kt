package com.shifthud.frozen

import com.shifthud.ui.paystub.*
import java.util.Locale

data class EndcapProduct(val name:String,val sku:String="",val family:String="",val category:String="Unassigned",val sourceNames:List<String> = emptyList(),val sourceSkus:List<String> = emptyList())
data class EndcapCandidate(val product:EndcapProduct,val location:String?,val source:String,val status:String)
data class RejectedEndcapCandidate(val text:String,val reason:String)
data class EndcapPage(val location:String?,val products:List<EndcapProduct>,val warning:String,
    val candidates:List<EndcapCandidate> = emptyList(),val rejected:List<RejectedEndcapCandidate> = emptyList())
const val TARGET_ENDCAP="Aisle 10 Front — Center"
val ENDCAP_CATEGORIES=listOf("Unassigned","Pizza — Bottom","Appetizers — Left","Entrées — Middle","Desserts — Right")
fun normalizeEndcap(value:String)=value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
fun sameEndcapProduct(a:EndcapProduct,b:EndcapProduct):Boolean=
    if(a.sku.isNotBlank() && b.sku.isNotBlank()) (a.sourceSkus+normalizeEndcap(a.sku)).any { it in b.sourceSkus+normalizeEndcap(b.sku) } else (a.sourceNames+normalizeEndcap(a.name)).any { it in b.sourceNames+normalizeEndcap(b.name) }
fun distinctEndcapProducts(rows:List<EndcapProduct>):List<EndcapProduct> = rows.sortedBy { it.sku.isBlank() }.fold(emptyList()) { result,row ->
    require(row.name.isNotBlank()) { "Product names cannot be blank." }
    if(result.any { sameEndcapProduct(it,row) }) result else result+row
}
internal fun detectedEndcap(text:String):String? {
    val clean=text.lowercase(Locale.ROOT).replace(Regex("a[i1l]s[l1i]e"),"aisle").replace(Regex("\\b[iIl1][oO0]\\b"),"10")
        .replace("fr0nt","front").replace("cenler","center").replace(Regex("\\s+")," ")
    val match=Regex("aisle\\s*(\\d+)\\s*(front|rear|back)(?:\\s*[-—–:]?\\s*(center|centre|left|right))?").find(clean) ?: return null
    val side=match.groupValues[3].replace("centre","center")
    if(side.isEmpty())return null
    return "Aisle ${match.groupValues[1]} ${match.groupValues[2].replaceFirstChar { it.uppercase() }} — ${side.replaceFirstChar { it.uppercase() }}"
}

/** Keeps geometry and every plausible row. Location association never gates product extraction. */
fun parseEndcapPage(document:OcrDocument):EndcapPage {
    val rows=mutableListOf<MutableList<OcrPiece>>()
    document.elements.sortedWith(compareBy({it.top},{it.left})).forEach { piece ->
        val row=rows.lastOrNull();val anchor=row?.firstOrNull()
        if(anchor!=null && kotlin.math.abs((piece.top+piece.bottom)-(anchor.top+anchor.bottom))/2.0 < minOf(piece.bottom-piece.top,anchor.bottom-anchor.top)*0.55)row+=piece
        else rows+=mutableListOf(piece)
    }
    data class Section(val top:Int,val bottom:Int,val text:String,val location:String?)
    // Merged location cells can span rows or be returned after product blocks.
    val locationLines=document.lines.map { it.bounds }.filter { Regex("(?i)ais[l1i]e|a1sle|endcap").containsMatchIn(it.text) }
    val sections=locationLines.map { heading ->
        val continuation=document.lines.map { it.bounds }.filter { line ->
            line.top>=heading.top && line.top<=heading.bottom+(heading.bottom-heading.top)*3 &&
                kotlin.math.abs(line.left-heading.left)<maxOf(80,heading.right-heading.left) &&
                (line===heading || Regex("(?i)^(endcap|ais[l1i]e|a1sle|[1i]?[0o]?\\s*(front|rear|back)|front|center|centre|left|right|[-—–\\s]+)").containsMatchIn(line.text.trim()))
        }.sortedBy { it.top }
        val text=continuation.joinToString(" ") { it.text }
        Section(heading.top,continuation.maxOfOrNull { it.bottom } ?: heading.bottom,text,detectedEndcap(text))
    }.distinctBy { it.top to it.location }.sortedBy { it.top }
    var descriptionLeft:Int?=null;var descriptionRight=Int.MAX_VALUE;var skuLeft:Int?=null
    val accepted=mutableListOf<EndcapCandidate>();val rejected=mutableListOf<RejectedEndcapCandidate>()
    var lastBottom:Int?=null
    rows.forEach { raw ->
        val pieces=raw.sortedBy { it.left };val y=pieces.minOf { it.top };val bottom=pieces.maxOf { it.bottom }
        val source=pieces.joinToString(" ") { it.text }
        val header=pieces.indexOfFirst { Regex("(?i)^(product\\s+|item\\s+)?description$").matches(it.text.trim()) }
        if(header>=0) {
            descriptionLeft=if(header>0 && pieces[header-1].text.equals("Product",true))pieces[header-1].left else pieces[header].left
            descriptionRight=pieces.drop(header+1).firstOrNull()?.left ?: Int.MAX_VALUE
            val sku=pieces.indexOfFirst { Regex("(?i)^(item|sku|item number|item #)$").matches(it.text.trim()) }
            if(sku>=0) { skuLeft=pieces[sku].left }
            rejected+=RejectedEndcapCandidate(source,"Table header");lastBottom=null;return@forEach
        }
        val section=sections.lastOrNull { it.top<=y }
        // Remove location-cell fragments even when horizontally aligned with a product row.
        val cells=pieces.filterNot { p -> locationLines.any { l -> p.left>=l.left && p.right<=l.right+3 && p.top>=l.top && p.bottom<=l.bottom+3 && !Regex("\\b\\d{4,12}\\b").containsMatchIn(l.text) } ||
            Regex("(?i)^(endcap|aisle|front|rear|back|center|centre|left|right|[-—–])$").matches(p.text.trim()) }
        val filtered=cells.joinToString(" ") { it.text }.trim()
        if(filtered.isBlank() || Regex("(?i)^(store display|detailed display|page\\b|total\\b|promotion\\b|week ending|item number description)").containsMatchIn(filtered)) {
            rejected+=RejectedEndcapCandidate(source,"Heading or display metadata");lastBottom=null;return@forEach
        }
        val numeric=Regex("^\\d{4,12}$")
        var sku="";var name=""
        if(descriptionLeft!=null) {
            name=cells.filter { it.left>=descriptionLeft!!-16 && it.left<descriptionRight-16 }.joinToString(" ") { it.text }.trim()
            sku=cells.firstOrNull { numeric.matches(it.text) && it.left<descriptionLeft!!-16 && (skuLeft==null || it.left>=skuLeft!!-16) }?.text.orEmpty()
        } else {
            val match=Regex("^\\s*(\\d{4,12})\\s+(.+)$").find(filtered)
            sku=match?.groupValues?.get(1).orEmpty()
            name=(match?.groupValues?.get(2) ?: filtered).replace(Regex("\\s+(?:\\$?\\d[\\d.,]*\\s*)+$"),"").trim()
        }
        if(name.count { it.isLetter() }<3) {
            rejected+=RejectedEndcapCandidate(source,if(sku.isNotBlank()) "Item number without a readable description; add/correct manually" else "No readable product description (numeric or non-product row)")
            lastBottom=null;return@forEach
        }
        val location=section?.location
        val previous=accepted.lastOrNull()
        val continuation=sku.isBlank() && descriptionLeft!=null && previous!=null && previous.product.sku.isNotBlank() &&
            previous.location==location && lastBottom!=null && y-lastBottom!! <= (bottom-y)*0.8 && y>=lastBottom!!
        if(continuation) {
            accepted[accepted.lastIndex]=previous!!.copy(product=previous.product.copy(name=previous.product.name+" "+name),source=previous.source+"\n"+source,status="Wrapped description — verify")
        } else {
            accepted+=EndcapCandidate(EndcapProduct(name,sku),location,source,
                if(location==null) "Unassigned — select this row only after checking its display" else if(location!=TARGET_ENDCAP) "Other display — excluded until selected" else if(sku.isBlank()) "Name-only — verify row boundary" else "Recognized — review required")
        }
        lastBottom=bottom
    }
    val locations=sections.mapNotNull { it.location }.distinct()
    val target=locations.singleOrNull()
    val warning=when {
        document.lines.isEmpty()->"OCR recognized no text. Crop, rotate, or choose a clearer photo."
        accepted.isEmpty()->"OCR recognized text but no products. Inspect diagnostics, crop/retry, or add products manually."
        accepted.any { it.location==null }->"Products were recognized but display association is uncertain. Select the relevant rows and confirm their display."
        else->"Check each product and exclude unrelated sections before confirming."
    }
    return EndcapPage(target,accepted.map { it.product },warning,accepted,rejected)
}

const val ENDCAP_LAYOUT_REFERENCE="3 doors · 5 shelf levels. Bottom: pizza across all doors. Left: appetizers. Middle: entrées. Right: desserts. Popular products generally near eye level; adjust above/below for physical fit."
fun endcapProductIdentity(product:EndcapProduct)=if(product.sku.isNotBlank()) "sku:${normalizeEndcap(product.sku)}" else "name:${normalizeEndcap(product.name)}"
fun endcapTaskContains(task:FrozenTask,product:EndcapProduct):Boolean =
    if(task.family.isNotBlank())task.eligible.any { sameEndcapProduct(it,product) }
    else sameEndcapProduct(EndcapProduct(task.title,task.sku,sourceNames=task.sourceNames,sourceSkus=task.sourceSkus),product) || (product.sku.isBlank() && normalizeEndcap(product.name) in task.sourceNames)
fun endcapImportPreview(tasks:List<FrozenTask>,week:java.time.LocalDate,location:String,products:List<EndcapProduct>):List<String> {
    val plan="$week|${normalizeEndcap(location)}";val old=tasks.filter { it.plan==plan }
    return distinctEndcapProducts(products).map { product ->
        val prior=old.firstOrNull { endcapTaskContains(it,product) }
        if(prior!=null) "Keep existing: ${prior.title} — corrections and progress unchanged"
        else if(product.family.isNotBlank() && old.any { normalizeEndcap(it.family)==normalizeEndcap(product.family) }) "Add eligible variety: ${product.name} → ${product.family} (existing family title, category, corrections and progress unchanged)"
        else "Add: ${product.name}${if(product.family.isBlank()) "" else " → ${product.family}"}"
    }
}
