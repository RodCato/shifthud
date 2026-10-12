package com.shifthud.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Value-first visual emphasis with label-first screen-reader order; no fixed text height. */
@Composable fun Metric(label: String, value: String, tag: String, primary: Boolean = false, supporting: String? = null) {
    Column(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,modifier=Modifier.fillMaxWidth(),style=if(primary) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
            fontWeight=if(primary) FontWeight.Bold else FontWeight.SemiBold)
        supporting?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun SectionHeading(text: String) {
    Text(text,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.semantics { heading() })
}
