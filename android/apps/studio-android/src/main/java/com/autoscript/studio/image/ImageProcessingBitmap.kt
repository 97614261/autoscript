package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Reject before decode; no implicit sampling or display-density rescale. */
internal fun decodeWorkbenchBitmap(file: File): Bitmap {
    require(file.isFile && file.length() in 1..24L * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    ImagePixelOperation("gray").dimensions(bounds.outWidth, bounds.outHeight)
    return requireNotNull(BitmapFactory.decodeFile(file.path,
        BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
}
