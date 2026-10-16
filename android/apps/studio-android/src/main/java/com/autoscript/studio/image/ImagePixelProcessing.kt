package com.autoscript.studio

/** Preview counterparts of Screen.transform/Screen.crop, with no SDK/native dependency. */
internal data class ImagePixelOperation(
    val mode: String,
    val width: Int = 0,
    val height: Int = 0,
    val roi: ImageToolCodeGen.Roi? = null,
    val threshold: Int = 128,
) {
    fun dimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        require(sourceWidth in 1..4096 && sourceHeight in 1..4096 &&
            sourceWidth.toLong() * sourceHeight <= MAX_PIXELS) { "原图超过400万像素预览预算" }
        require(mode == "crop" || roi == null)
        require(mode == "resize" || width == 0 && height == 0)
        require(threshold in 0..255) { "二值阈值须为0–255" }
        val size = when (mode) {
            "gray", "threshold", "rotate180", "flipX", "flipY" -> sourceWidth to sourceHeight
            "rotate90", "rotate270" -> sourceHeight to sourceWidth
            "resize" -> width to height
            "crop" -> templateCropDimensions(sourceWidth, sourceHeight, requireNotNull(roi))
            else -> error("不支持的图片处理方式")
        }
        require(size.first in 1..4096 && size.second in 1..4096 && size.first.toLong() * size.second <= MAX_PIXELS) {
            "输出尺寸必须为1–4096且不超过400万像素；不会自动缩放"
        }
        return size
    }

    companion object { const val MAX_PIXELS = 4_194_304L }
}

internal data class ProcessedImagePixels(val width: Int, val height: Int, val argb: IntArray)

internal fun processImagePixels(source: IntArray, width: Int, height: Int,
    operation: ImagePixelOperation, checkpoint: () -> Unit): ProcessedImagePixels {
    val (outWidth, outHeight) = operation.dimensions(width, height)
    require(source.size.toLong() == width.toLong() * height)
    checkpoint()
    val output = IntArray(outWidth * outHeight)
    val grayscale = operation.mode == "gray" || operation.mode == "threshold"
    for (y in 0 until outHeight) {
        checkpoint()
        for (x in 0 until outWidth) {
            val (sx, sy) = when (operation.mode) {
                "rotate90" -> y to height - 1 - x
                "rotate180" -> width - 1 - x to height - 1 - y
                "rotate270" -> width - 1 - y to x
                "flipX" -> width - 1 - x to y
                "flipY" -> x to height - 1 - y
                "resize" -> (x.toLong() * width / outWidth).toInt() to (y.toLong() * height / outHeight).toInt()
                "crop" -> x + requireNotNull(operation.roi).left to y + operation.roi.top
                else -> x to y
            }
            val pixel = source[sy * width + sx]
            output[y * outWidth + x] = if (grayscale) {
                val luminance = (((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29 + 128) ushr 8)
                val gray = if (operation.mode == "threshold") { if (luminance >= operation.threshold) 255 else 0 } else luminance
                (pixel and -0x1000000) or (gray shl 16) or (gray shl 8) or gray
            } else pixel
        }
    }
    checkpoint()
    return ProcessedImagePixels(outWidth, outHeight, output)
}
