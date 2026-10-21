package com.shifthud.frozen

import com.shifthud.ui.paystub.*

import android.content.Context
import android.net.Uri
import android.graphics.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Images/URIs are memory-only. No uploads, persistent grants, image copies, or OCR logging. */
class EndcapOcr(private val context:Context) {
    suspend fun read(uri:Uri, readingOrder:(List<OcrPiece>)->String = ::ocrRows):String =
        readingOrder(readDocument(uri).lines.map { it.bounds }).take(20000)

    suspend fun readDocument(uri:Uri, rotation:Int=0):OcrDocument=scan(uri,rotation).document

    suspend fun scan(uri:Uri,rotation:Int=0,crop:EndcapCrop=EndcapCrop()):EndcapScan {
        val decoded=decodeEndcapImage(context,uri,rotation,crop)
        val bitmap=decoded.bitmap
        val recognizer=try { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        catch(error:Throwable) {bitmap.recycle();throw error}
        // The native task must finish before recycling its bitmap, including cancellation/navigation.
        return withContext(NonCancellable) {
            try { suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener { result ->
                    fun piece(text:String, box:Rect?)=box?.let{OcrPiece(text,it.left,it.top,it.right,it.bottom)}
                    val blocks=result.textBlocks.mapNotNull { block ->
                        val bounds=piece(block.text,block.boundingBox)?:return@mapNotNull null
                        OcrBlock(bounds,block.lines.mapNotNull { line ->
                            val lineBounds=piece(line.text,line.boundingBox)?:return@mapNotNull null
                            OcrLine(lineBounds,line.elements.mapNotNull{piece(it.text,it.boundingBox)})
                        })
                    }
                    continuation.resume(EndcapScan(OcrDocument(blocks),result.text,bitmap.width,bitmap.height,decoded.orientation))
                }.addOnFailureListener{continuation.resumeWithException(it)}
            } } finally {recognizer.close();bitmap.recycle()}
        }
    }
}

data class EndcapScan(val document:OcrDocument,val recognizedText:String,val width:Int,val height:Int,val orientation:String)
