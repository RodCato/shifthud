package com.shifthud.frozen

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
import kotlinx.coroutines.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private data class ReviewPage(val id:String=UUID.randomUUID().toString(),val uri:Uri?,val rotation:Int=0,
    val location:String="",val rows:List<EndcapProduct> = emptyList(),val warning:String="",val confirmed:Boolean=false)

/** Drafts and picker grants stay in memory. Closing the import never creates tasks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EndcapImportScreen(vm:FrozenTasksViewModel,date:LocalDate,back:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var pages by remember { mutableStateOf(emptyList<ReviewPage>()) }
    var reading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var week by remember { mutableStateOf(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))) }
    var chooseWeek by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val cameraDir=remember { File(context.cacheDir,"endcap_camera").apply { mkdirs() } }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) {
        // Remove camera leftovers from an interrupted previous import. Never touch user-selected images.
        cameraDir.listFiles()?.forEach { it.delete() }
        onDispose { cameraDir.listFiles()?.forEach { it.delete() } }
    }
    fun update(page:ReviewPage) { pages=pages.map { if(it.id==page.id) page else it } }
    fun recognize(uris:List<Uri>) {
        if(reading)return
        reading=true;message=null
        scope.launch {
            try {
                for(uri in uris) {
                    val result=parseEndcapPage(EndcapOcr(context).readDocument(uri))
                    pages=pages+ReviewPage(uri=uri,location=result.location.orEmpty(),rows=result.products,warning=result.warning)
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="Image could not be read. Try another photo or add products manually." }
            finally { reading=false }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> recognize(uris) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraFile?.let { file -> if(success) recognize(listOf(FileProvider.getUriForFile(context,"${context.packageName}.endcap.camera",file))) else file.delete() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(enabled=!reading && !busy,onClick={cancel=true}) { Text("Cancel import") }
        Text("Review Endcap Products",Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall)
        Text("Use photos only when permitted by workplace policy. Recognition stays on this device. Images are not attached or saved with tasks. Review each page before confirming.",Modifier.fillMaxWidth())
        Button(enabled=!reading && !busy,onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}) { Text("Select photos / pages",Modifier.fillMaxWidth()) }
        OutlinedButton(enabled=!reading && !busy,onClick={
            try {
                val file=File.createTempFile("capture-",".jpg",cameraDir);cameraFile=file
                camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.endcap.camera",file))
            } catch(_:Exception) { message="Camera is unavailable. Select an existing image instead." }
        }) { Text("Take photo",Modifier.fillMaxWidth()) }
        TextButton(enabled=!reading && !busy,onClick={pages=pages+ReviewPage(uri=null,rows=listOf(EndcapProduct("")))}) { Text("Add manual page") }
        TextButton(enabled=!busy,onClick={chooseWeek=true}) { Text("Promotional week: $week – ${week.plusDays(6)}",Modifier.fillMaxWidth()) }
        if(reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        pages.forEachIndexed { index,page -> key(page.id) {
            HorizontalDivider()
            Text("Page ${index+1}",style=MaterialTheme.typography.titleMedium)
            page.uri?.let { uri ->
                var preview by remember(uri,page.rotation) { mutableStateOf<android.graphics.Bitmap?>(null) }
                DisposableEffect(preview) { val bitmap=preview;onDispose { bitmap?.recycle() } }
                LaunchedEffect(uri,page.rotation) {
                    preview=withContext(Dispatchers.IO) {
                        runCatching {
                            val original=if(android.os.Build.VERSION.SDK_INT>=28) android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver,uri)) { decoder,info,_ ->
                                val scale=minOf(1.0,800.0/maxOf(info.size.width,info.size.height))
                                decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
                                decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                            else {
                                val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                                context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it,null,options) }
                                options.inSampleSize=1
                                while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>800)options.inSampleSize*=2
                                options.inJustDecodeBounds=false
                                context.contentResolver.openInputStream(uri).use { requireNotNull(android.graphics.BitmapFactory.decodeStream(it,null,options)) }
                            }
                            android.graphics.Bitmap.createBitmap(original,0,0,original.width,original.height,android.graphics.Matrix().apply { postRotate(page.rotation.toFloat()) },true).also { if(it!==original)original.recycle() }
                        }.getOrNull()
                    }
                }
                preview?.let { Image(it.asImageBitmap(),"Source page ${index+1}",Modifier.fillMaxWidth().heightIn(max=280.dp)) }
                TextButton(enabled=!reading && !busy,onClick={
                    reading=true
                    scope.launch { try {
                        val rotation=(page.rotation+90)%360
                        val result=parseEndcapPage(EndcapOcr(context).readDocument(uri,rotation))
                        update(page.copy(rotation=rotation,location=result.location.orEmpty(),rows=result.products,warning=result.warning,confirmed=false))
                    } catch(e:CancellationException) { throw e } catch(_:Exception) { message="Rotation could not be read. Try another image." } finally {reading=false} }
                }) { Text("Rotate 90° and re-read (replaces page edits)",Modifier.fillMaxWidth()) }
            }
            Text(page.warning,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(page.location,{update(page.copy(location=it,confirmed=false))},enabled=!reading && !busy,label={Text("Display location — confirm for this page")},modifier=Modifier.fillMaxWidth())
            page.rows.forEachIndexed { rowIndex,row ->
                OutlinedTextField(row.name,{value->update(page.copy(rows=page.rows.toMutableList().apply { set(rowIndex,row.copy(name=value)) },confirmed=false))},enabled=!reading && !busy,label={Text("Product ${rowIndex+1} description")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(row.sku,{value->update(page.copy(rows=page.rows.toMutableList().apply { set(rowIndex,row.copy(sku=value)) },confirmed=false))},enabled=!reading && !busy,label={Text("Item / SKU (optional)")},modifier=Modifier.fillMaxWidth())
                TextButton(enabled=!busy && !reading,onClick={update(page.copy(rows=page.rows.filterIndexed { i,_->i!=rowIndex },confirmed=false))}) { Text("Remove product ${rowIndex+1}") }
            }
            TextButton(enabled=!reading && !busy,onClick={update(page.copy(rows=page.rows+EndcapProduct(""),confirmed=false))}) { Text("Add product to page ${index+1}") }
            Row {
                Checkbox(page.confirmed,{update(page.copy(confirmed=it))},enabled=!reading && !busy && page.location.isNotBlank() && page.rows.isNotEmpty() && page.rows.all { it.name.isNotBlank() && it.name.length<=160 && it.sku.length<=60 },modifier=Modifier.semanticsForPage(index))
                Text("I checked this page's location and products",Modifier.weight(1f))
            }
            TextButton(enabled=!busy && !reading,onClick={pages=pages.filterNot { it.id==page.id }}) { Text("Remove page ${index+1}") }
        } }
        val sameLocation=pages.map { normalizeEndcap(it.location) }.distinct().size==1
        if(pages.isNotEmpty() && !sameLocation) Text("Pages contain different displays. Remove unrelated pages and import each display separately.")
        Text("Confirm creates one pick task per product in this display/week. Repeated SKUs or full names are deduplicated; existing progress stays unchanged.",style=MaterialTheme.typography.bodySmall)
        Button(enabled=pages.isNotEmpty() && pages.all { it.confirmed && it.location.length<=160 } && sameLocation && !reading && !busy,onClick={
            vm.importProducts(date,week,pages.first().location,pages.flatMap { it.rows }) { back() }
        }) { Text("Confirm products and create pick list",Modifier.fillMaxWidth()) }
    }
    if(cancel) AlertDialog(onDismissRequest={cancel=false},title={Text("Discard import draft?")},text={Text("No tasks have been created. Existing tasks are unchanged.")},confirmButton={TextButton(onClick=back) { Text("Discard") }},dismissButton={TextButton(onClick={cancel=false}) { Text("Keep reviewing") }})
    if(chooseWeek) {
        val state=rememberDatePickerState(initialSelectedDateMillis=week.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={chooseWeek=false},confirmButton={TextButton(onClick={state.selectedDateMillis?.let { week=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)) };chooseWeek=false}) { Text("Use Saturday–Friday week") }},dismissButton={TextButton(onClick={chooseWeek=false}) { Text("Cancel") }}) { DatePicker(state) }
    }
}

private fun Modifier.semanticsForPage(index:Int)=this.then(Modifier.semantics { contentDescription="Confirm page ${index+1} location and products" })
