package com.shifthud.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shifthud.ShiftHudApplication
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun BackupSettingsSection() {
    var open by rememberSaveable{mutableStateOf(false)}
    Text("Data Management",style=MaterialTheme.typography.titleLarge)
    OutlinedButton(onClick={open=true}){Text("Backup & Restore")}
    if(open)BackupDialog{open=false}
}
@Composable private fun BackupDialog(close:()->Unit) {
    val app=LocalContext.current.applicationContext as ShiftHudApplication
    val manager=app.backups
    val scope=rememberCoroutineScope()
    var password by remember{mutableStateOf("")}
    var confirmation by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    var encrypted by remember{mutableStateOf<ByteArray?>(null)}
    var selected by remember{mutableStateOf<android.net.Uri?>(null)}
    var preview by remember{mutableStateOf<BackupSnapshot?>(null)}
    var replaceConfirmed by remember{mutableStateOf(false)}
    var soundWarnings by remember{mutableStateOf<List<String>>(emptyList())}
    var existing by remember{mutableStateOf<Map<String,Int>>(emptyMap())}
    fun run(block:suspend ()->Unit) {
        busy=true;message=null
        scope.launch {
            try{block()}catch(e:Exception){message=e.message?:"Operation failed. Existing records have not been replaced."}
            finally{busy=false;password="";confirmation=""}
        }
    }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val bytes=encrypted
        if(uri!=null && bytes!=null)run{try{manager.writeVerified(uri,bytes);message="Encrypted backup written, closed, and verified. Keep its password separately; ShiftHUD cannot recover a forgotten password."}finally{bytes.fill(0);encrypted=null}}
        else {encrypted?.fill(0);encrypted=null;message=if(uri==null)"Export canceled." else "Export was interrupted. This destination is not a verified backup; discard it and create a new backup."}
    }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        selected=uri;preview=null;replaceConfirmed=false;password="";confirmation="";message=null
    }
    Dialog(onDismissRequest={if(!busy)close()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("Backup & Restore",style=MaterialTheme.typography.headlineMedium)
                Text("Encrypted, portable backups. No account or network connection is required. Your selected document provider may sync the encrypted file.")
                Text("Back up after clocking out. Restore replaces all supported data and requires no active shift. A forgotten password cannot be recovered.")
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(preview==null) {
                    OutlinedTextField(password,{password=it.take(1024)},label={Text("Backup password")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    if(selected==null) {
                        OutlinedTextField(confirmation,{confirmation=it.take(1024)},label={Text("Confirm password")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                        Button(enabled=!busy,onClick={
                            if(password.length<12 || password!=confirmation)message="Use at least 12 characters and enter the same password twice."
                            else {val chars=password.toCharArray();run{try{encrypted=manager.export(chars);create.launch("ShiftHUD-${java.time.LocalDate.now()}.shifthud")}finally{chars.fill('\u0000')}}}
                        }){Text("CREATE ENCRYPTED BACKUP")}
                    } else {
                        Text("Backup selected. Enter its password to validate and preview.")
                        Button(enabled=!busy && password.isNotEmpty(),onClick={
                            val uri=selected!!;val chars=password.toCharArray()
                            run{try{val bytes=manager.readFile(uri);try{preview=manager.preview(bytes,chars);soundWarnings=manager.soundWarnings(preview!!);existing=manager.snapshot().counts}finally{bytes.fill(0)}}finally{chars.fill('\u0000')}}
                        }){Text("VALIDATE & PREVIEW")}
                    }
                    OutlinedButton(enabled=!busy,onClick={pick.launch(arrayOf("*/*"))}){Text("RESTORE FROM BACKUP")}
                }
                preview?.let { p->
                    Text("Compatible backup · Ready for review",style=MaterialTheme.typography.titleLarge)
                    val created=Instant.parse(p.manifest.getString("created")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM))
                    Text("Created: $created\nApp: ${p.manifest.getString("appVersion")} · Format: ${p.manifest.getInt("format")}\nSource time zone: ${p.manifest.getString("zone")}")
                    p.counts.forEach{(name,count)->Text("${name.replace('_',' ')}: $count (currently ${existing[name]?:0})")}
                    p.manifest.optJSONObject("preferenceCounts")?.let{counts->counts.namesSet().sorted().forEach{Text("${it.replace('_',' ')} preferences: ${counts.getInt(it)}")}}
                    soundWarnings.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
                    Text("Includes pay-rate history, favorites/recent finds, and reminder/weekly-target preferences.")
                    Text("Android notification permission, channel sounds/vibration, and widget placement do not transfer. Review them in Android notification settings after restore; reselect custom sounds on this phone.")
                    Text("REPLACE EXISTING DATA\nAll current records and supported preferences will be replaced. Records are not merged. No changes have been made during preview.",color=MaterialTheme.colorScheme.error)
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                        Checkbox(replaceConfirmed,{replaceConfirmed=it},enabled=!busy)
                        Text("I reviewed the counts and want to replace this installation's data.",modifier=Modifier.weight(1f))
                    }
                    Button(enabled=!busy && replaceConfirmed,onClick={run{message=manager.restore(p);preview=null;selected=null;replaceConfirmed=false}}){Text("CONFIRM REPLACEMENT")}
                    TextButton(enabled=!busy,onClick={preview=null;selected=null;replaceConfirmed=false}){Text("CANCEL RESTORE")}
                }
                message?.let{Text(it)}
                TextButton(enabled=!busy,onClick=close){Text("CLOSE")}
            }
        }
    }
}
