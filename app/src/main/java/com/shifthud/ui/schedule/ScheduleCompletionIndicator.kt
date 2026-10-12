package com.shifthud.ui.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Decorative only: the expandable row announces its textual status, without checkbox actions. */
@Composable fun ScheduleCompletionIndicator(status: ScheduleCompletionStatus) {
    val color=if(status==ScheduleCompletionStatus.SCHEDULED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        val stroke=2.dp.toPx()
        when(status) {
            ScheduleCompletionStatus.SCHEDULED, ScheduleCompletionStatus.COMPLETED -> {
                drawRoundRect(color,topLeft=Offset(stroke,stroke),size=size.copy(width=size.width-2*stroke,height=size.height-2*stroke),cornerRadius=CornerRadius(stroke),style=Stroke(stroke))
                if(status==ScheduleCompletionStatus.COMPLETED) drawPath(Path().apply {
                    moveTo(size.width*.25f,size.height*.5f);lineTo(size.width*.43f,size.height*.68f);lineTo(size.width*.76f,size.height*.32f)
                },color,style=Stroke(stroke))
            }
            ScheduleCompletionStatus.WORKING -> {
                drawCircle(color,style=Stroke(stroke),radius=size.minDimension/2-stroke)
                drawCircle(color,radius=size.minDimension*.2f)
            }
            ScheduleCompletionStatus.ON_LUNCH -> {
                drawLine(color,Offset(size.width*.35f,size.height*.2f),Offset(size.width*.35f,size.height*.8f),stroke)
                drawLine(color,Offset(size.width*.65f,size.height*.2f),Offset(size.width*.65f,size.height*.8f),stroke)
            }
        }
    }
}
