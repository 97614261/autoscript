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
    val leftOffsets = IntArray(width)
    val rightOffsets = IntArray(width)
    val leftWeights = IntArray(width)
    val rightWeights = IntArray(width)
    for (x in 0 until width) {
        if (x % 64 == 0) checkpoint()
        val fx = ((x + .5) * scaleX - .5).toFloat()
        var left = floor(fx).toInt()
        var fraction = fx - left
        if (left < 0) { left = 0; fraction = 0f }
        if (left >= roi.width - 1) { left = roi.width - 1; fraction = 0f }
        leftOffsets[x] = (roi.x + if (halfScale) 2 * x else left) * 4
        rightOffsets[x] = (roi.x + if (halfScale) 2 * x + 1 else (left + 1).coerceAtMost(roi.width - 1)) * 4
        leftWeights[x] = round((1f - fraction) * 2048f).toInt()
        rightWeights[x] = round(fraction * 2048f).toInt()
    }
    for (y in 0 until 48) {
        checkpoint()
        val fy = ((y + .5) * scaleY - .5).toFloat()
        val top = floor(fy).toInt()
        val fractionY = fy - top
        val weightTop = round((1f - fractionY) * 2048f).toInt()
        val weightBottom = round(fractionY * 2048f).toInt()
        val upperRow = (roi.y + if (halfScale) 2 * y else top.coerceIn(0, roi.height - 1)) * metadata[2]
        val lowerRow = (roi.y + if (halfScale) 2 * y + 1 else (top + 1).coerceIn(0, roi.height - 1)) * metadata[2]
        for (x in 0 until width) {
            for (c in 0..2) {
                val left = leftOffsets[x] + channels[c]
                val right = rightOffsets[x] + channels[c]
                val a0 = source.get(upperRow + left).toInt() and 255
                val a1 = source.get(upperRow + right).toInt() and 255
                val b0 = source.get(lowerRow + left).toInt() and 255
                val b1 = source.get(lowerRow + right).toInt() and 255
                val sample = if (halfScale) {
                    (a0 + a1 + b0 + b1 + 2) / 4
                } else {
                    val upper = a0 * leftWeights[x] + a1 * rightWeights[x]
                    val lower = b0 * leftWeights[x] + b1 * rightWeights[x]
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
