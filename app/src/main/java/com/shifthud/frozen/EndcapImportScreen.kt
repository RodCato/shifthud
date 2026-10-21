package com.shifthud.frozen

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.ui.components.ExpandableSection
import kotlinx.coroutines.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID

internal data class EndcapReviewRow(val product:EndcapProduct,val location:String?=null,val source:String="",val status:String="Manual entry",val included:Boolean=true)
internal data class ReviewEndcapPage(val id:String=UUID.randomUUID().toString(),val uri:Uri?,val rotation:Int=0,val crop:EndcapCrop=EndcapCrop(),
    val location:String="",val rows:List<EndcapReviewRow> = emptyList(),val warning:String="",val confirmed:Boolean=false,val scan:EndcapScan?=null,val parsed:EndcapPage?=null)
internal fun reviewRows(page:EndcapPage)=page.candidates.map { EndcapReviewRow(it.product.copy(sourceNames=listOf(normalizeEndcap(it.product.name)),sourceSkus=listOfNotNull(it.product.sku.takeIf { sku->sku.isNotBlank() }?.let(::normalizeEndcap))),it.location,it.source,it.status,it.location==TARGET_ENDCAP) }
internal fun mergeEndcapRetry(old:List<EndcapReviewRow>,fresh:List<EndcapReviewRow>)=old+fresh.filter { row->old.none { sameEndcapProduct(it.product,row.product) } }

/** Drafts/diagnostics remain local and in memory; originals are never overwritten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EndcapImportScreen(vm:FrozenTasksViewModel,date:LocalDate,initialImages:List<Uri> = emptyList(),back:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var pages by remember { mutableStateOf(emptyList<ReviewEndcapPage>()) }
    var reading by remember { mutableStateOf(false) };var message by remember { mutableStateOf<String?>(null) }
    var week by remember { mutableStateOf(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))) }
    var reference by remember { mutableStateOf(ENDCAP_LAYOUT_REFERENCE) }
    var chooseWeek by remember { mutableStateOf(false) };var cancel by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<ReviewEndcapPage?>(null) }
    var cropping by remember { mutableStateOf<Pair<ReviewEndcapPage,Bitmap>?>(null) }
    val busy by vm.busy.collectAsStateWithLifecycle();val error by vm.error.collectAsStateWithLifecycle();val saved by vm.tasks.collectAsStateWithLifecycle()
    val cameraDir=remember { File(context.cacheDir,"endcap_camera").apply { mkdirs() } }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) { cameraDir.listFiles()?.forEach { it.delete() };onDispose { cameraDir.listFiles()?.forEach { it.delete() } } }
    fun update(page:ReviewEndcapPage) { pages=pages.map { if(it.id==page.id)page else it } }
    fun recognize(uris:List<Uri>) {
        if(reading)return
        reading=true;message=null
        scope.launch {
            try { for(uri in uris) {
                try {
                    val scan=EndcapOcr(context).scan(uri);val parsed=parseEndcapPage(scan.document)
                    pages=pages+ReviewEndcapPage(uri=uri,location=parsed.location.orEmpty(),rows=reviewRows(parsed),warning=parsed.warning,scan=scan,parsed=parsed)
                } catch(e:CancellationException) {throw e} catch(_:Exception) {
                    pages=pages+ReviewEndcapPage(uri=uri,warning="Image decoding/recognition failed. Retry, crop, or add products manually.")
                }
            } } finally {reading=false}
        }
    }
    fun rerun(page:ReviewEndcapPage,keep:Boolean) {
        retry=null;reading=true;message=null
        scope.launch { try {
            val scan=EndcapOcr(context).scan(requireNotNull(page.uri),page.rotation,page.crop);val parsed=parseEndcapPage(scan.document)
            val fresh=reviewRows(parsed)
            update(page.copy(scan=scan,parsed=parsed,warning=parsed.warning,rows=if(keep)mergeEndcapRetry(page.rows,fresh) else fresh,
                location=if(keep && page.location.isNotBlank())page.location else parsed.location.orEmpty(),confirmed=false))
        } catch(e:CancellationException) {throw e} catch(_:Exception) {message="Retry failed. Your previous rows and edits are unchanged."} finally {reading=false} }
    }
    LaunchedEffect(Unit) { if(initialImages.isNotEmpty())recognize(initialImages) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { recognize(it) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraFile?.let { file->if(success)recognize(listOf(FileProvider.getUriForFile(context,"${context.packageName}.endcap.camera",file))) else file.delete() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(enabled=!reading && !busy,onClick={cancel=true}) { Text("Cancel import") }
        Text("Review Endcap Products",Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall)
        Text("Use photos only when permitted by workplace policy. OCR and diagnostics stay on this device. Review rows and their display before saving.",Modifier.fillMaxWidth())
        Button(enabled=!reading && !busy,onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}) { Text("Select photos / pages",Modifier.fillMaxWidth()) }
        OutlinedButton(enabled=!reading && !busy,onClick={try {
            val file=File.createTempFile("capture-",".jpg",cameraDir);cameraFile=file
            camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.endcap.camera",file))
        } catch(_:Exception) {message="Camera unavailable. Select an existing image instead."} }) { Text("Take photo",Modifier.fillMaxWidth()) }
        TextButton(enabled=!reading && !busy,onClick={pages=pages+ReviewEndcapPage(uri=null,rows=listOf(EndcapReviewRow(EndcapProduct(""))))}) { Text("Add manual page") }
        TextButton(enabled=!busy,onClick={chooseWeek=true}) { Text("Promotional week: $week – ${week.plusDays(6)}",Modifier.fillMaxWidth()) }
        Text("Week initially comes from the selected workflow, not OCR. Confirm it against the sheet.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        ExpandableSection("Layout reference / optional families","Edit reference; grouping is optional") {
            OutlinedTextField(reference,{reference=it},label={Text("Editable layout reference")},modifier=Modifier.fillMaxWidth())
            Text("Use each row's Optional family field to group, split, or move varieties. Leave it blank for an individual SKU. Families create one representative-case task; eligible varieties remain distinct.",Modifier.fillMaxWidth())
        }
        if(reading)LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let {Text(it,color=MaterialTheme.colorScheme.error)};error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        pages.forEachIndexed { index,page -> key(page.id) {
            fun rowUpdate(i:Int,row:EndcapReviewRow) {update(page.copy(rows=page.rows.toMutableList().apply {set(i,row)},confirmed=false))}
            HorizontalDivider();Text("Page ${index+1}",style=MaterialTheme.typography.titleMedium)
            page.uri?.let { uri ->
                var preview by remember(uri,page.rotation) { mutableStateOf<Bitmap?>(null) }
                DisposableEffect(preview) { val bitmap=preview;onDispose {bitmap?.recycle()} }
                LaunchedEffect(uri,page.rotation) {preview=runCatching {decodeEndcapImage(context,uri,page.rotation,maxPixels=640_000,maxEdge=1000).bitmap}.getOrNull()}
                preview?.let {Image(it.asImageBitmap(),"Source page ${index+1}",Modifier.fillMaxWidth().heightIn(max=280.dp))}
                TextButton(enabled=!reading && !busy && preview!=null,onClick={cropping=page to requireNotNull(preview)}) {Text("Crop to Display Section",Modifier.fillMaxWidth())}
                TextButton(enabled=!reading && !busy,onClick={retry=page}) {Text("Retry OCR",Modifier.fillMaxWidth())}
                TextButton(enabled=!reading && !busy,onClick={retry=page.copy(rotation=(page.rotation+90)%360,crop=EndcapCrop())}) {Text("Rotate 90° and retry",Modifier.fillMaxWidth())}
                EndcapDiagnostics(page)
            }
            Text(page.warning,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
            if(page.rows.isEmpty()) {
                Text("No products recognized",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium)
                Text("Crop and retry, rotate and retry, or add products manually. At least one valid selected product is required.",Modifier.fillMaxWidth())
            }
            OutlinedTextField(page.location,{update(page.copy(location=it,confirmed=false))},enabled=!reading && !busy,label={Text("Display location — confirm for this page")},modifier=Modifier.fillMaxWidth())
            page.rows.forEachIndexed { rowIndex,row ->
                Text("${row.location ?: "Unassigned display"} · ${row.status}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(row.included,{rowUpdate(rowIndex,row.copy(included=it))},enabled=!reading && !busy,modifier=Modifier.semantics {contentDescription="Include product ${rowIndex+1} on page ${index+1}"})
                    Text("Include in confirmed display",Modifier.weight(1f))
                }
                OutlinedTextField(row.product.name,{rowUpdate(rowIndex,row.copy(product=row.product.copy(name=it),status="Manually corrected"))},enabled=!reading && !busy,label={Text("Product ${rowIndex+1} description")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(row.product.sku,{rowUpdate(rowIndex,row.copy(product=row.product.copy(sku=it),status="Manually corrected"))},enabled=!reading && !busy,label={Text("Item / SKU (optional)")},modifier=Modifier.fillMaxWidth())
                if(row.source.isNotBlank())ExpandableSection("Source text · product ${rowIndex+1}","Recognized text before correction") {Text(row.source,Modifier.fillMaxWidth())}
                ExpandableSection("Optional family · product ${rowIndex+1}",row.product.family.ifBlank {"Ungrouped"}) {
                    OutlinedTextField(row.product.family,{rowUpdate(rowIndex,row.copy(product=row.product.copy(family=it)))},label={Text("Family name (blank = individual)")},modifier=Modifier.fillMaxWidth(),enabled=!reading && !busy)
                    val suggestion=row.product.name.trim().substringBefore(' ')
                    if(suggestion.length>=3)TextButton(enabled=!reading && !busy,onClick={rowUpdate(rowIndex,row.copy(product=row.product.copy(family=suggestion)))}) {Text("Use suggested family: $suggestion",Modifier.fillMaxWidth())}
                    var categories by remember {mutableStateOf(false)}
                    TextButton(enabled=!reading && !busy,onClick={categories=true}) {Text("Layout: ${row.product.category}",Modifier.fillMaxWidth())}
                    DropdownMenu(categories,{categories=false}) {ENDCAP_CATEGORIES.forEach { category -> DropdownMenuItem(text={Text(category)},onClick={
                        categories=false
                        update(page.copy(rows=page.rows.mapIndexed { i,other->if(i==rowIndex || (row.product.family.isNotBlank() && normalizeEndcap(other.product.family)==normalizeEndcap(row.product.family)))other.copy(product=other.product.copy(category=category)) else other },confirmed=false))
                    })} }
                }
                TextButton(enabled=!busy && !reading,onClick={update(page.copy(rows=page.rows.filterIndexed { i,_->i!=rowIndex },confirmed=false))}) {Text("Remove product ${rowIndex+1}")}
            }
            page.rows.map {it.product.family}.filter {it.isNotBlank()}.distinct().forEach { family ->
                ExpandableSection("Family: $family","Rename all members or split into individual products") {
                    var familyName by remember(family) { mutableStateOf(family) }
                    OutlinedTextField(familyName,{familyName=it},label={Text("Rename family")},modifier=Modifier.fillMaxWidth(),enabled=!reading && !busy)
                    TextButton(enabled=!reading && !busy && familyName.isNotBlank() && familyName.length<=160,onClick={update(page.copy(rows=page.rows.map {if(it.product.family==family)it.copy(product=it.product.copy(family=familyName.trim())) else it},confirmed=false))}) {Text("Apply family name")}
                    TextButton(enabled=!reading && !busy,onClick={update(page.copy(rows=page.rows.map {if(it.product.family==family)it.copy(product=it.product.copy(family="")) else it},confirmed=false))}) {Text("Split family into individual products",Modifier.fillMaxWidth())}
                }
            }
            TextButton(enabled=!reading && !busy,onClick={update(page.copy(rows=page.rows+EndcapReviewRow(EndcapProduct("")),confirmed=false))}) {Text("Add product to page ${index+1}")}
            val selected=page.rows.filter {it.included}
            if(selected.isEmpty())Text("Select or add at least one product, then confirm the display location.",Modifier.fillMaxWidth())
            Row {
                Checkbox(page.confirmed,{update(page.copy(confirmed=it))},enabled=!reading && !busy && page.location.isNotBlank() && selected.isNotEmpty() && selected.all {it.product.name.isNotBlank() && it.product.name.length<=160 && it.product.sku.length<=60 && it.product.family.length<=160},modifier=Modifier.semantics {contentDescription="Confirm page ${index+1} location and products"})
                Text("I checked this page's location and selected products",Modifier.weight(1f))
            }
            TextButton(enabled=!busy && !reading,onClick={pages=pages.filterNot {it.id==page.id}}) {Text("Remove page ${index+1}")}
        } }
        val sameLocation=pages.map {normalizeEndcap(it.location)}.distinct().size==1
        if(pages.isNotEmpty() && !sameLocation)Text("Pages contain different displays. Remove unrelated pages and import each display separately.")
        Text("Review proposed additions before saving. Existing corrections and completed work are preserved; reimports never silently replace tasks.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        Button(enabled=pages.isNotEmpty() && pages.all {it.confirmed && it.location.length<=160} && sameLocation && reference.length<=1500 && !reading && !busy,onClick={confirming=true}) {Text("Confirm products and create pick list",Modifier.fillMaxWidth())}
    }
    cropping?.let { (page,bitmap)->EndcapCropDialog(bitmap,page.crop,{cropping=null}) {crop->cropping=null;retry=page.copy(crop=crop)} }
    retry?.let { page -> AlertDialog(onDismissRequest={retry=null},title={Text("Retry OCR without losing edits")},text={Text("Keep existing edits and add new candidates, or explicitly replace this page's rows. Cancel leaves everything unchanged.")},
        confirmButton={TextButton(onClick={rerun(page,true)}) {Text("Keep edits and add candidates")}},dismissButton={Column {
            TextButton(onClick={rerun(page,false)}) {Text("Replace this page's rows")}
            TextButton(onClick={retry=null}) {Text("Cancel")}
        }}) }
    if(confirming) {
        val products=pages.flatMap {it.rows.filter { row->row.included }.map {row->row.product}}
        val preview=endcapImportPreview(saved.orEmpty(),week,pages.first().location,products)
        AlertDialog(onDismissRequest={if(!busy)confirming=false},title={Text("Confirm picking checklist")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${pages.first().location}\n$week – ${week.plusDays(6)}")
            preview.forEach {Text(it,Modifier.fillMaxWidth().padding(vertical=4.dp))}
            Text("Families need one representative case; additional varieties can be chosen later. Final Fill stays independent.")
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=!busy,onClick={vm.importProducts(date,week,pages.first().location,products,reference) {confirming=false;back()}}) {Text("Save reviewed pick list")}},dismissButton={TextButton(enabled=!busy,onClick={confirming=false}) {Text("Keep reviewing")}})
    }
    if(cancel)AlertDialog(onDismissRequest={cancel=false},title={Text("Discard import draft?")},text={Text("No tasks have been created. Existing tasks are unchanged.")},confirmButton={TextButton(onClick=back) {Text("Discard")}},dismissButton={TextButton(onClick={cancel=false}) {Text("Keep reviewing")}})
    if(chooseWeek) {
        val state=rememberDatePickerState(initialSelectedDateMillis=week.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={chooseWeek=false},confirmButton={TextButton(onClick={state.selectedDateMillis?.let {week=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))};chooseWeek=false}) {Text("Use Saturday–Friday week")}},dismissButton={TextButton(onClick={chooseWeek=false}) {Text("Cancel")}}) {DatePicker(state)}
    }
}

@Composable private fun EndcapDiagnostics(page:ReviewEndcapPage) {
    ExpandableSection("OCR Diagnostics","Local diagnostics · expand to inspect") {
        val scan=page.scan;val parsed=page.parsed
        if(scan==null)Text("No OCR result: image decoding or recognition failed, or this is a manual page.",Modifier.fillMaxWidth())
        else {
            Text("OCR image: ${scan.width} × ${scan.height}\n${scan.orientation}\nBlocks: ${scan.document.blocks.size} · Lines: ${scan.document.lines.size}",Modifier.fillMaxWidth())
            val rejected=parsed?.rejected.orEmpty().filterNot {it.reason.contains("header",true) || it.reason.contains("metadata",true)}
            Text("Candidate product rows: ${(parsed?.candidates?.size ?: 0)+rejected.size}\nAccepted products: ${parsed?.products?.size ?: 0}\nRejected candidates: ${rejected.size}\nDisplay: ${parsed?.location ?: "Unassigned / multiple displays"}",Modifier.fillMaxWidth())
            parsed?.warning?.let {Text(it,Modifier.fillMaxWidth())}
            ExpandableSection("View Recognized Text","Raw ML Kit output before parsing") {Text(scan.recognizedText.ifBlank {"OCR recognized no text."},Modifier.fillMaxWidth())}
            ExpandableSection("View Rejected Candidates","Candidate text and rejection reasons") {
                parsed?.rejected.orEmpty().forEach {Text("${it.reason}\n${it.text}",Modifier.fillMaxWidth().padding(vertical=4.dp))}
                if(parsed?.rejected.isNullOrEmpty())Text("No rejected candidates.")
            }
        }
    }
}
