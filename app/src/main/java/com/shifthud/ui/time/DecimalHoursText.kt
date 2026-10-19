package com.shifthud.ui.time

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.shifthud.ShiftHudApplication
import java.time.Duration

/** Optional override for previews/tests; production observes the persisted display preference. */
val LocalDecimalHours = staticCompositionLocalOf<Boolean?> { null }
@Composable fun showDecimalHours(): Boolean {
    LocalDecimalHours.current?.let { return it }
    val preferences=(LocalContext.current.applicationContext as ShiftHudApplication).preferences
    return preferences.showDecimalHours.collectAsState(initial=true).value
}
@Composable fun DecimalHoursText(duration: Duration?, label: String = "") {
    if(duration!=null && showDecimalHours()) Text(
        "$label${decimalTime(duration,LocalConfiguration.current.locales[0])} decimal hours",
        modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
