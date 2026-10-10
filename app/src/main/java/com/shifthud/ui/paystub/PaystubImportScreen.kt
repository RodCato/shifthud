package com.shifthud.ui.paystub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.domain.paystub.extractPaystub
import com.shifthud.ui.payroll.PaycheckEditor
import java.time.LocalDate

@Composable fun PaystubImportScreen(vm:PaystubImportViewModel,start:LocalDate,end:LocalDate,back:()->Unit) {
    val rates by vm.rates.collectAsStateWithLifecycle(initialValue=emptyList())
    val text by vm.text.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val reviewState=rememberSaveableStateHolder()
    var detailsExpanded by rememberSaveable{mutableStateOf(false)}
    var reviewing by rememberSaveable{mutableStateOf(false)}
    var saved by rememberSaveable{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(PickVisualMedia()){uri->if(uri!=null){reviewState.removeState(text);reviewing=false;saved=false;vm.read(uri)}}
    val extraction=remember(text){extractPaystub(text)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick=back){Text("‹ PAYCHECKS")}
        Text("Import Paystub Screenshot",style=MaterialTheme.typography.headlineMedium)
        Text("Choose an image, review recognized values, then confirm. Recognition runs on this device. ShiftHUD does not upload or keep the image.")
        Button(enabled=!busy,onClick={picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))}){Text("SELECT IMAGE")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let{Text(it)}
        if(saved)Text("Paycheck saved. Return to Paychecks and select its work period.")
        if(text.isNotBlank()) {
            Text("Paystub recognized · Review required",style=MaterialTheme.typography.titleMedium)
            Text("Period: ${extraction.start?:"Unknown"} – ${extraction.end?:"Unknown"}")
            Text("Regular hours: ${extraction.regular?:"Unknown"} · Gross: ${com.shifthud.domain.payroll.centsInput(extraction.gross).ifBlank{"Unknown"}}")
            TextButton(onClick={detailsExpanded=!detailsExpanded}){Text(if(detailsExpanded)"HIDE OCR DETAILS" else "EXPAND OCR DETAILS")}
            if(detailsExpanded)Text(text,style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy,onClick={reviewing=true}){Text("REVIEW PAYROLL FIELDS")}
            Text("Unclear, cropped, multi-column or unlabeled values may need manual entry. Nothing is saved until you confirm the review.")
        }
    }
    if(reviewing && text.isNotBlank())reviewState.SaveableStateProvider(text) { PaycheckEditor(initial=remember(text){extraction.draft(start,end)},busy=busy,review=extraction,rates=rates,save={p,done->vm.save(p){error->if(error==null)saved=true;done(error)}}){reviewing=false} }
}
