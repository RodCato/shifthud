package com.shifthud.ui.quickfind

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.domain.model.*

@Composable
fun QuickFindScreen(vm: QuickFindViewModel, focusRequest: Int) {
    val data by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var guideEditing by rememberSaveable { mutableStateOf(false) }
    var guideId by rememberSaveable { mutableStateOf<Long?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var lastFocusRequest by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(focusRequest) {
        if (lastFocusRequest != focusRequest) { selectedId=null;editing=false;guideEditing=false;deleting=false;lastFocusRequest=focusRequest }
        if (!editing && !guideEditing && selectedId == null) { focus.requestFocus(); keyboard?.show() }
    }
    fun editGuide(id: Long?) { guideId=id;guideEditing=true;keyboard?.hide() }
    fun edit(id: Long?) { selectedId=null;editId=id;editing=true;keyboard?.hide() }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal=16.dp)) {
        Text("QUICK FIND",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
        OutlinedTextField(data.query,{vm.query.value=it},label={Text("Search items…")},singleLine=true,
            modifier=Modifier.fillMaxWidth().focusRequester(focus),
            trailingIcon={if(data.query.isNotEmpty()) TextButton(onClick={vm.query.value=""}) {Text("Clear")}})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            TextButton(onClick={editGuide(null)},enabled=data.loaded && !busy){Text("+ ADD AISLE")}
            TextButton(onClick={edit(null)},enabled=data.loaded && !busy){Text("+ ADD ITEM")}
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick=vm::clearError){Text("Dismiss")} }
        if (!data.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=16.dp)) {
            if(normalizeQuickFind(data.query).isNotEmpty()) {
                if(data.loaded && data.results.matches.isEmpty() && data.guideMatches.isEmpty()) item {Text("No matches. Try another name, category, or location.")}
                items(data.results.matches,key={"match-${it.id}"}) { item -> QuickFindRow(item,!busy) {selectedId=item.id;keyboard?.hide();vm.select(item.id)} }
                if(data.guideMatches.isNotEmpty()) item {Text("AISLE GUIDE",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=12.dp))}
                items(data.guideMatches,key={"guide-match-${it.id}"}) { entry -> AisleGuideRow(entry,!busy) {editGuide(entry.id)} }
            } else {
                if(data.loaded && data.items.isEmpty()) item {
                    Text("No saved items yet.",style=MaterialTheme.typography.titleMedium)
                    Text("Save the items and locations you don’t want to keep looking up.")
                    Button(onClick={edit(null)},enabled=!busy){Text("ADD FIRST ITEM")}
                }
                item {Text("FAVORITES",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=8.dp))}
                if(data.results.favorites.isEmpty()) item {Text("Favorite an item to keep it here.")}
                items(data.results.favorites,key={"favorite-${it.id}"}) { item -> QuickFindRow(item,!busy) {selectedId=item.id;keyboard?.hide();vm.select(item.id)} }
                item {Text("AISLE GUIDE",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=12.dp))}
                if(data.guide.isEmpty()) item {Text("No aisle mappings yet. Use + ADD AISLE to save your store’s categories.")}
                items(data.guide,key={"guide-${it.id}"}) { entry -> AisleGuideRow(entry,!busy) {editGuide(entry.id)} }
                item {Text("RECENT FINDS",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=12.dp))}
                if(data.results.recent.isEmpty()) item {Text("Select a result to keep it in Recent Finds.")}
                items(data.results.recent,key={"recent-${it.id}"}) { item -> QuickFindRow(item,!busy) {selectedId=item.id;keyboard?.hide();vm.select(item.id)} }
            }
            item {Text("Personal, local-only notes. Not inventory or official store data.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=16.dp))}
        }
    }
    if(guideEditing && data.loaded) key(guideId) {
        AisleGuideEditor(data.guide.firstOrNull {it.id==guideId},vm,busy) {guideEditing=false}
    }
    val selected=data.items.firstOrNull {it.id==selectedId}
    if(selected!=null) AlertDialog(onDismissRequest={selectedId=null;deleting=false},title={Text(selected.name)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(aisleLabel(selected.aisle),style=MaterialTheme.typography.titleLarge)
            if(selected.locationNote.isNotEmpty()) Text(selected.locationNote)
            if(selected.aliases.isNotEmpty()) Text("Aliases: ${selected.aliases.joinToString(", ")}")
            TextButton(enabled=!busy,onClick={vm.favorite(selected.id,!selected.isFavorite)}){Text(if(selected.isFavorite) "★ UNFAVORITE" else "☆ FAVORITE")}
            Row {
                TextButton(onClick={edit(selected.id)},enabled=!busy){Text("EDIT")}
                TextButton(onClick={deleting=true},enabled=!busy){Text("DELETE")}
            }
        }
    },confirmButton={TextButton(onClick={selectedId=null}){Text("Done")}})
    if(deleting && selected!=null) AlertDialog(onDismissRequest={deleting=false},title={Text("Delete item?")},text={Text("Delete ${selected.name} from your saved items?")},
        confirmButton={TextButton(enabled=!busy,onClick={vm.delete(selected.id){deleting=false;selectedId=null}}){Text("Delete item")}},
        dismissButton={TextButton(onClick={deleting=false},enabled=!busy){Text("Cancel")}})
    if(editing) key(editId) {
        QuickFindEditor(data.items.firstOrNull {it.id==editId},vm,busy,{editing=false}) { id -> editId=id }
    }
}

@Composable private fun QuickFindRow(item: QuickFindItem, enabled: Boolean, select: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=select).padding(vertical=10.dp)) {
        Text((if(item.isFavorite) "★ " else "")+item.name,style=MaterialTheme.typography.titleMedium)
        Text(aisleLabel(item.aisle),style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.primary)
        if(item.locationNote.isNotEmpty()) Text(item.locationNote,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}
