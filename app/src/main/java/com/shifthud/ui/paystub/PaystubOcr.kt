package com.shifthud.ui.paystub

import android.content.Context
import android.net.Uri
import android.graphics.*
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Images/URIs are memory-only. No uploads, persistent grants, image copies, or OCR logging. */
class PaystubOcr(private val context:Context) {
    suspend fun read(uri:Uri):String {
        val bitmap=withContext(Dispatchers.IO) {
            if(Build.VERSION.SDK_INT>=28)ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_->
                val scale=minOf(1.0,3000.0/maxOf(info.size.width,info.size.height));decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            } else {
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,options)}
                require(options.outWidth>0 && options.outHeight>0){"Image could not be decoded."}
                options.inSampleSize=1
                while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>3000)options.inSampleSize*=2
                options.inJustDecodeBounds=false
                val original=context.contentResolver.openInputStream(uri).use{requireNotNull(BitmapFactory.decodeStream(it,null,options))}
                val orientation=context.contentResolver.openInputStream(uri).use{ExifInterface(requireNotNull(it)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}
                val matrix=Matrix().apply { when(orientation){
                    ExifInterface.ORIENTATION_ROTATE_90->postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180->postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270->postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL->postScale(-1f,1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL->postScale(1f,-1f)
                    ExifInterface.ORIENTATION_TRANSPOSE->{postRotate(90f);postScale(-1f,1f)}
                    ExifInterface.ORIENTATION_TRANSVERSE->{postRotate(270f);postScale(-1f,1f)}
                } }
                Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true).also{if(it!==original)original.recycle()}
            }
        }
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        // The native task must finish before recycling its bitmap, including cancellation/navigation.
        return withContext(NonCancellable) {
            try { suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener { result ->
                    val pieces=result.textBlocks.flatMap{it.lines}.mapNotNull{line->line.boundingBox?.let{OcrPiece(line.text,it.left,it.top,it.right,it.bottom)}}
                    continuation.resume(ocrRows(pieces).take(20000))
                }.addOnFailureListener{continuation.resumeWithException(it)}
            } } finally {recognizer.close();bitmap.recycle()}
        }
    }
}
data class OcrPiece(val text:String,val left:Int,val top:Int,val right:Int,val bottom:Int)
/** Join horizontally aligned OCR fragments; never select a guessed current/YTD numeric column. */
fun ocrRows(pieces:List<OcrPiece>):String {
    val rows=mutableListOf<MutableList<OcrPiece>>()
    for(piece in pieces.sortedBy{it.top}) {
        val row=rows.lastOrNull()
        val first=row?.firstOrNull()
        if(first!=null && kotlin.math.abs((piece.top+piece.bottom)-(first.top+first.bottom))/2.0 < minOf(piece.bottom-piece.top,first.bottom-first.top)*0.45)row+=piece
        else rows+=mutableListOf(piece)
    }
    return rows.joinToString("\n"){row->row.sortedBy{it.left}.joinToString(" "){it.text}}
}
