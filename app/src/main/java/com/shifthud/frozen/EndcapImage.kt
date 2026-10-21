package com.shifthud.frozen

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Normalized coordinates in EXIF-oriented, manually rotated image space. */
data class EndcapCrop(val left:Float=0f,val top:Float=0f,val right:Float=1f,val bottom:Float=1f) {
    init { require(left>=0 && top>=0 && right<=1 && bottom<=1 && right-left>=0.0099f && bottom-top>=0.0099f) }
}
data class EndcapImage(val bitmap:Bitmap,val orientation:String,val sourceWidth:Int,val sourceHeight:Int)

@Suppress("DEPRECATION")
suspend fun decodeEndcapImage(context:Context,uri:Uri,rotation:Int=0,crop:EndcapCrop=EndcapCrop(),maxPixels:Long=12_000_000,maxEdge:Int=6000):EndcapImage {
    var owned:Bitmap?=null
    try { return withContext(Dispatchers.IO) {
    require(rotation in listOf(0,90,180,270));require(maxPixels in 1..12_000_000 && maxEdge in 1..6000)
    val resolver=context.contentResolver
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
    require(bounds.outWidth>0 && bounds.outHeight>0) { "Image could not be decoded." }
    val orientation=resolver.openInputStream(uri).use { stream ->
        runCatching { ExifInterface(requireNotNull(stream)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }
    val transform=Matrix().apply {
        when(orientation) {
            2->postScale(-1f,1f);3->postRotate(180f);4->postScale(1f,-1f)
            5->{postRotate(90f);postScale(-1f,1f)};6->postRotate(90f)
            7->{postRotate(270f);postScale(-1f,1f)};8->postRotate(270f)
        }
        postRotate(rotation.toFloat())
    }
    val oriented=RectF(0f,0f,bounds.outWidth.toFloat(),bounds.outHeight.toFloat()).also { transform.mapRect(it) }
    transform.postTranslate(-oriented.left,-oriented.top)
    val selected=RectF(crop.left*oriented.width(),crop.top*oriented.height(),crop.right*oriented.width(),crop.bottom*oriented.height())
    val inverse=Matrix();check(transform.invert(inverse));inverse.mapRect(selected)
    val region=Rect(floor(selected.left).toInt().coerceIn(0,bounds.outWidth-1),floor(selected.top).toInt().coerceIn(0,bounds.outHeight-1),ceil(selected.right).toInt().coerceIn(1,bounds.outWidth),ceil(selected.bottom).toInt().coerceIn(1,bounds.outHeight))
    val options=BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1 }
    while(region.width().toLong()*region.height()/options.inSampleSize/options.inSampleSize>maxPixels || maxOf(region.width(),region.height())/options.inSampleSize>maxEdge) options.inSampleSize*=2
    val raw=resolver.openInputStream(uri).use { stream ->
        val decoder=requireNotNull(BitmapRegionDecoder.newInstance(requireNotNull(stream),false))
        try { requireNotNull(decoder.decodeRegion(region,options)) } finally { decoder.recycle() }
    }
    val rotated=try { Bitmap.createBitmap(raw,0,0,raw.width,raw.height,transform,true) } catch(e:Throwable) {raw.recycle();throw e}
    if(rotated!==raw)raw.recycle()
    owned=rotated
    EndcapImage(rotated,"EXIF $orientation + manual $rotation° · crop ${if(crop==EndcapCrop()) "full image" else "selected region"}",bounds.outWidth,bounds.outHeight)
}
    } catch(error:Throwable) { owned?.recycle();throw error }
}
