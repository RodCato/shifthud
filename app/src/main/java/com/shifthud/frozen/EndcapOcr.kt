package com.shifthud.frozen

import com.shifthud.ui.paystub.*

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
class EndcapOcr(private val context:Context) {
    suspend fun read(uri:Uri, readingOrder:(List<OcrPiece>)->String = ::ocrRows):String =
        readingOrder(readDocument(uri).lines.map { it.bounds }).take(20000)

    suspend fun readDocument(uri:Uri, rotation:Int=0):OcrDocument {
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
                recognizer.process(InputImage.fromBitmap(bitmap,rotation)).addOnSuccessListener { result ->
                    fun piece(text:String, box:Rect?)=box?.let{OcrPiece(text,it.left,it.top,it.right,it.bottom)}
                    val blocks=result.textBlocks.mapNotNull { block ->
                        val bounds=piece(block.text,block.boundingBox)?:return@mapNotNull null
                        OcrBlock(bounds,block.lines.mapNotNull { line ->
                            val lineBounds=piece(line.text,line.boundingBox)?:return@mapNotNull null
                            OcrLine(lineBounds,line.elements.mapNotNull{piece(it.text,it.boundingBox)})
                        })
                    }
                    continuation.resume(OcrDocument(blocks))
                }.addOnFailureListener{continuation.resumeWithException(it)}
            } } finally {recognizer.close();bitmap.recycle()}
        }
    }
}