package com.shifthud.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun ExpandableSection(title:String, subtitle:String, forceOpen:Boolean=false, leading:(@Composable () -> Unit)?=null, subtitleContent:(@Composable () -> Unit)?=null, titleContent:(@Composable () -> Unit)?=null, content:@Composable ColumnScope.()->Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val open=forceOpen || expanded
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(role=Role.Button,onClickLabel=if(open) "Collapse $title" else "Expand $title") { expanded=!expanded }
            .semantics(mergeDescendants=true) { stateDescription=if(open) "Expanded" else "Collapsed" }
            .padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                if(titleContent!=null) titleContent() else Text(title,modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                if(subtitleContent!=null) subtitleContent() else Text(subtitle,modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if(open) "−" else "+",modifier=Modifier.clearAndSetSemantics {})
        }
        if(open) Column(Modifier.fillMaxWidth().padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        HorizontalDivider()
    }
}
