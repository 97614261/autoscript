package com.autoscript.runtime.service

import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/** Public deterministic pixels only; no screen capture, Root, app interaction or model execution. */
class OnnxPreprocessingParityFixture {
    fun run(): JSONObject {
        NativeCvBuffers.load()
        val cases = JSONArray()
        var pass = true
        val sizes = listOf(1 to 1, 3 to 2, 9 to 6, 31 to 17, 73 to 48, 101 to 96, 319 to 49,
            503 to 57, 641 to 40, 96 to 96, 320 to 96, 97 to 194, 1920 to 1080, 4000 to 200)
        val started = System.nanoTime()
        for ((w, h) in sizes) for (format in 1..2) {
            check(System.nanoTime() - started < 60_000_000_000L) { "PREPROCESS_PARITY_TIMEOUT" }
            val frameWidth = w + 4
            val frameHeight = h + 4
            val stride = frameWidth * 4 + 12
            val pixels = ByteBuffer.allocateDirect(stride * frameHeight)
            for (y in 0 until frameHeight) for (x in 0 until frameWidth) {
                val r = (x * 37 + y * 17) and 255
                val g = (x * 11 + y * 53 + 1) and 255
                val b = (x * 71 + y * 5 + 2) and 255
                val offset = y * stride + x * 4
                pixels.put(offset, (if (format == 1) r else b).toByte())
                pixels.put(offset + 1, g.toByte())
                pixels.put(offset + 2, (if (format == 1) b else r).toByte())
                pixels.put(offset + 3, ((x + y) and 255).toByte())
            }
            val metadata = intArrayOf(frameWidth, frameHeight, stride, format)
            val options = intArrayOf(2, 2, w + 2, h + 2, 0)
            val expected = original(pixels, metadata, options)
            val candidate = prepareOnnxImage(pixels, metadata, options)
            check(expected.size == candidate.tensor.size)
            var changed = 0
            var maxError = 0f
            for (i in expected.indices) {
                if (expected[i].toBits() != candidate.tensor[i].toBits()) changed++
                maxError = max(maxError, kotlin.math.abs(expected[i] - candidate.tensor[i]))
            }
            cases.put(JSONObject().put("roiWidth", w).put("roiHeight", h).put("format", format)
                .put("stride", stride).put("differentTensorValues", changed).put("maxAbsoluteError", maxError))
            if (changed != 0) pass = false
        }
        return JSONObject().put("scope", "synthetic preprocessing only; exact tensor bits")
            .put("opencvVersion", "4.10.0").put("cases", cases).put("pass", pass)
    }

    private fun original(pixels: ByteBuffer, metadata: IntArray, options: IntArray): FloatArray {
        val roi = checkedVisionRect(metadata[0], metadata[1], options)
        val width = ceil(48.0 * roi.width / roi.height).toInt().coerceAtLeast(1)
        val padded = max(320, width)
        val result = FloatArray(3 * 48 * padded)
        val source = NativeCvBuffers.image(pixels, metadata)
        var crop: Mat? = null
        var bgr: Mat? = null
        var resized: Mat? = null
        try {
            crop = source.submat(Rect(roi.x, roi.y, roi.width, roi.height))
            bgr = Mat()
            resized = Mat()
            Imgproc.cvtColor(crop, bgr, if (metadata[3] == 1) Imgproc.COLOR_RGBA2BGR else Imgproc.COLOR_BGRA2BGR)
            Imgproc.resize(bgr, resized, Size(width.toDouble(), 48.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val data = ByteArray(48 * width * 3)
            resized.get(0, 0, data)
            for (y in 0 until 48) for (x in 0 until width) for (c in 0..2) {
                result[c * 48 * padded + y * padded + x] = ((data[(y * width + x) * 3 + c].toInt() and 255) / 127.5f) - 1f
            }
            return result
        } finally { releaseVisionResources({ resized?.release() }, { bgr?.release() }, { crop?.release() }, { source.release() }) }
    }
}
