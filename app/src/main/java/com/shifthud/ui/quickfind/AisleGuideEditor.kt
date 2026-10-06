package com.shifthud.ui.quickfind

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shifthud.domain.model.*

@Composable
internal fun AisleGuideRow(entry: AisleGuideEntry, enabled: Boolean, edit: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=edit).padding(vertical=10.dp)) {
        Text(aisleLabel(entry.aisle),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        Text(guideCategoryLabel(entry.categories),style=MaterialTheme.typography.bodyLarge)
        Text("Edit aisle guide",style=MaterialTheme.typography.labelSmall)
    }
    HorizontalDivider()
}

@Composable
internal fun AisleGuideEditor(entry: AisleGuideEntry?, vm: QuickFindViewModel, busy: Boolean, dismiss: () -> Unit) {
    var aisle by rememberSaveable { mutableStateOf(entry?.aisle ?: "") }
    var categories by rememberSaveable { mutableStateOf(entry?.categories ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text(if(entry==null) "Add aisle guide" else "Edit aisle guide")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(aisle,{aisle=it;error=null},label={Text("Aisle / location *")},singleLine=true,enabled=!busy)
            OutlinedTextField(categories,{categories=it;error=null},label={Text("Categories *")},supportingText={Text("Separate categories with commas")},enabled=!busy)
            Text("Your personal store reference. Changes also update the expanded widget.")
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(entry!=null) TextButton(onClick={deleting=true},enabled=!busy){Text("DELETE MAPPING")}
        }
    },confirmButton={TextButton(enabled=!busy,onClick={vm.saveGuide(entry?.id,aisle,categories){if(it==null)dismiss() else error=it}}){Text("Save mapping")}},
        dismissButton={TextButton(onClick=dismiss,enabled=!busy){Text("Cancel")}})
    if(deleting && entry!=null) AlertDialog(onDismissRequest={deleting=false},title={Text("Delete mapping?")},text={Text("Remove ${aisleLabel(entry.aisle)} from your guide and widget?")},
        confirmButton={TextButton(enabled=!busy,onClick={vm.deleteGuide(entry.id,dismiss)}){Text("Delete mapping")}},
        dismissButton={TextButton(onClick={deleting=false},enabled=!busy){Text("Cancel")}})
}
