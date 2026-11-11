package com.shifthud.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.shifthud.data.preferences.ShiftPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun EmployeeNumberSettings(preferences:ShiftPreferences) {
    val saved by preferences.employeeNumber.collectAsState(initial=null)
    EmployeeNumberField(saved,preferences::setEmployeeNumber)
}

@Composable internal fun EmployeeNumberField(saved:String?,save:suspend (String)->Unit) {
    var draft by remember(saved) {mutableStateOf(saved.orEmpty())}
    var saving by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    OutlinedTextField(draft,{draft=it;message=null},label={Text("Employee Number")},singleLine=true,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),enabled=saved!=null && !saving,
        trailingIcon={if(draft.isNotEmpty()) TextButton(onClick={draft=""},enabled=!saving) {Text("Clear")}})
    Text("Shown on your home-screen widget.",style=MaterialTheme.typography.bodySmall)
    Button(enabled=saved!=null && !saving && draft.trim()!=saved,onClick={scope.launch {
        saving=true
        try {save(draft);message="Employee number saved."}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {message="Could not save employee number. Please try again."}
        finally {saving=false}
    }}) {Text("Save employee number")}
    message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
}
