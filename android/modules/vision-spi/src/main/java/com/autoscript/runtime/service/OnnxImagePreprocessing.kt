package com.autoscript.runtime.service

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/** Bounded SDK-independent BGR preprocessing, with exact-tensor OpenCV synthetic parity coverage. */
data class OnnxImageInput(val width: Int, val paddedWidth: Int, val tensor: FloatArray)

fun prepareOnnxImage(pixels: ByteBuffer, metadata: IntArray, options: IntArray,
    checkpoint: () -> Unit = {}): OnnxImageInput {
    validateVisionBuffer(pixels.capacity(), pixels.remaining(), pixels.isDirect, metadata)
    val roi = checkedVisionRect(metadata[0], metadata[1], options)
    val width = ceil(48.0 * roi.width / roi.height).toInt().coerceAtLeast(1)
    require(width <= 960)
    val padded = max(320, width)
    val tensor = FloatArray(3 * 48 * padded)
    val source = pixels.asReadOnlyBuffer()
    val scaleX = roi.width.toDouble() / width
    val scaleY = roi.height.toDouble() / 48
    val channels = if (metadata[3] == 1) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)
    val halfScale = roi.width == width * 2 && roi.height == 96
    fun pixel(x: Int, y: Int, channel: Int): Int = source.get(
        (roi.y + y.coerceIn(0, roi.height - 1)) * metadata[2] +
            (roi.x + x.coerceIn(0, roi.width - 1)) * 4 + channels[channel],
    ).toInt() and 255
    for (y in 0 until 48) {
        checkpoint()
        val fy = ((y + .5) * scaleY - .5).toFloat()
        val top = floor(fy).toInt()
        val fractionY = fy - top
        val weightTop = round((1f - fractionY) * 2048f).toInt()
        val weightBottom = round(fractionY * 2048f).toInt()
        for (x in 0 until width) {
            val fx = ((x + .5) * scaleX - .5).toFloat()
            var left = floor(fx).toInt()
            var fractionX = fx - left
            if (left < 0) { left = 0; fractionX = 0f }
            if (left >= roi.width - 1) { left = roi.width - 1; fractionX = 0f }
            val weightLeft = round((1f - fractionX) * 2048f).toInt()
            val weightRight = round(fractionX * 2048f).toInt()
            for (c in 0..2) {
                val sample = if (halfScale) {
                    (pixel(2*x, 2*y, c) + pixel(2*x+1, 2*y, c) +
                        pixel(2*x, 2*y+1, c) + pixel(2*x+1, 2*y+1, c) + 2) / 4
                } else {
                    val upper = pixel(left, top, c) * weightLeft + pixel(left+1, top, c) * weightRight
                    val lower = pixel(left, top+1, c) * weightLeft + pixel(left+1, top+1, c) * weightRight
                    val a = (upper ushr 4) * weightTop
                    val b = (lower ushr 4) * weightBottom
                    ((a ushr 16) + (b ushr 16) + 2) ushr 2
                }
                tensor[c * 48 * padded + y * padded + x] = sample / 127.5f - 1f
            }
        }
    }
    checkpoint()
    return OnnxImageInput(width, padded, tensor)
}
