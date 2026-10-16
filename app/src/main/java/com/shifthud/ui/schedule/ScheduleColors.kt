package com.shifthud.ui.schedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Schedule-only semantic accents; labels always carry the same meaning without color. */
internal data class ScheduleColors(val paid: Color, val lunch: Color, val attention: Color, val excess: Color)

@Composable internal fun scheduleColors(): ScheduleColors {
    val scheme=MaterialTheme.colorScheme
    val dark=scheme.surface.luminance()<0.5f
    return ScheduleColors(scheme.primary,
        if(dark) Color(0xFFACCEEF) else Color(0xFF285779),
        if(dark) Color(0xFFE6C078) else Color(0xFF755300),
        scheme.error)
}
