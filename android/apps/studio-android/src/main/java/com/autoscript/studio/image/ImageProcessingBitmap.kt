package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Reject before decode; no implicit sampling or display-density rescale. */
internal fun decodeWorkbenchBitmap(file: File,maxPixels:Int=4_194_304): Bitmap {
    require(file.isFile && file.length() in 1..24L * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    ImagePixelOperation("gray").dimensions(bounds.outWidth, bounds.outHeight)
    require(bounds.outWidth.toLong()*bounds.outHeight<=maxPixels && maxPixels in 1..4_194_304)
    return requireNotNull(BitmapFactory.decodeFile(file.path,
        BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
}

/** Runs a copied recipe from the owned frozen original, publishing only the final bitmap. */
internal fun processImagePipelineBitmap(source:Bitmap,pipeline:ImageProcessingPipeline,checkpoint:()->Unit):Bitmap {
    require(source.width==pipeline.sourceWidth && source.height==pipeline.sourceHeight)
    var pixels=IntArray(source.width*source.height);source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
    var width=source.width;var height=source.height
    for(operation in pipeline.operations) {
        checkpoint();val result=processImagePixels(pixels,width,height,operation,checkpoint)
        pixels=result.argb;width=result.width;height=result.height
    }
    checkpoint();return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
}
