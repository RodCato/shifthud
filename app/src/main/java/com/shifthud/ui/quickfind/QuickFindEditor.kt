package com.shifthud.ui.quickfind

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shifthud.data.repository.DuplicateQuickFindItem
import com.shifthud.domain.model.QuickFindItem

@Composable
internal fun QuickFindEditor(item: QuickFindItem?, vm: QuickFindViewModel, busy: Boolean, dismiss: () -> Unit, editExisting: (Long) -> Unit) {
    var name by rememberSaveable { mutableStateOf(item?.name ?: "") }
    var aisle by rememberSaveable { mutableStateOf(item?.aisle ?: "") }
    var note by rememberSaveable { mutableStateOf(item?.locationNote ?: "") }
    var aliases by rememberSaveable { mutableStateOf(item?.aliases?.joinToString(", ") ?: "") }
    var favorite by rememberSaveable { mutableStateOf(item?.isFavorite ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf<Long?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (item == null) "Add item" else "Edit item") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name=it;error=null;duplicate=null }, label={Text("Item name *")},singleLine=true,enabled=!busy)
                OutlinedTextField(aisle, {aisle=it;error=null},label={Text("Aisle / location *")},singleLine=true,enabled=!busy)
                OutlinedTextField(note,{note=it},label={Text("Location note")},enabled=!busy)
                OutlinedTextField(aliases,{aliases=it},label={Text("Aliases")},supportingText={Text("Separate aliases with commas")},enabled=!busy)
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(favorite,{favorite=it},enabled=!busy);Text("Favorite")
                }
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                duplicate?.let { id -> TextButton(onClick={editExisting(id)},enabled=!busy) {Text("EDIT EXISTING ITEM")} }
            }
        },
        confirmButton = { TextButton(enabled=!busy,onClick={
            vm.save(item?.id,name,aisle,note,aliases,favorite) { failure ->
                if(failure==null) dismiss() else {error=failure.message;duplicate=(failure as? DuplicateQuickFindItem)?.existingId}
            }
        }) {Text("Save item")} },
        dismissButton={TextButton(onClick=dismiss,enabled=!busy){Text("Cancel")}})
}
