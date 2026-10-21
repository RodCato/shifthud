package com.shifthud.frozen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable internal fun EndcapCropDialog(bitmap:Bitmap,initial:EndcapCrop,dismiss:()->Unit,apply:(EndcapCrop)->Unit) {
    var crop by remember { mutableStateOf(initial) }
    var anchor by remember { mutableStateOf(Offset.Zero) }
    AlertDialog(onDismissRequest=dismiss,title={Text("Crop to Display Section")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Drag a rectangle on the original image, or adjust the boundaries below. The original stays available.")
        Image(bitmap.asImageBitmap(),"Original image; crop selection",Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height)
            .pointerInput(Unit) { detectDragGestures(onDragStart={anchor=Offset((it.x/size.width).coerceIn(0f,1f),(it.y/size.height).coerceIn(0f,1f))}) { change,_ ->
                val end=Offset((change.position.x/size.width).coerceIn(0f,1f),(change.position.y/size.height).coerceIn(0f,1f))
                val left=minOf(anchor.x,end.x);val right=maxOf(anchor.x,end.x);val top=minOf(anchor.y,end.y);val bottom=maxOf(anchor.y,end.y)
                if(right-left>=0.01f && bottom-top>=0.01f)crop=EndcapCrop(left,top,right,bottom)
                change.consume()
            } }.drawWithContent { drawContent();drawRect(Color.Green,Offset(crop.left*size.width,crop.top*size.height),Size((crop.right-crop.left)*size.width,(crop.bottom-crop.top)*size.height),style=Stroke(3.dp.toPx())) })
        Text("Left ${(crop.left*100).toInt()}%");Slider(crop.left,{crop=crop.copy(left=it)},valueRange=0f..(crop.right-0.01f),modifier=Modifier.semantics { contentDescription="Crop left boundary" })
        Text("Right ${(crop.right*100).toInt()}%");Slider(crop.right,{crop=crop.copy(right=it)},valueRange=(crop.left+0.01f)..1f,modifier=Modifier.semantics { contentDescription="Crop right boundary" })
        Text("Top ${(crop.top*100).toInt()}%");Slider(crop.top,{crop=crop.copy(top=it)},valueRange=0f..(crop.bottom-0.01f),modifier=Modifier.semantics { contentDescription="Crop top boundary" })
        Text("Bottom ${(crop.bottom*100).toInt()}%");Slider(crop.bottom,{crop=crop.copy(bottom=it)},valueRange=(crop.top+0.01f)..1f,modifier=Modifier.semantics { contentDescription="Crop bottom boundary" })
        TextButton(onClick={crop=EndcapCrop()}) { Text("Use full image") }
    }},confirmButton={TextButton(onClick={apply(crop)}) { Text("Use crop and review retry") }},dismissButton={TextButton(onClick=dismiss) { Text("Cancel") }})
}
