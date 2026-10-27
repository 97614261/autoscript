package com.autoscript.runtime.service

import android.graphics.Bitmap
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Public software Bitmaps only. This does not capture a display or measure Root transport. */
internal object CaptureReadbackBenchmarkFixture {
    fun run(): JSONObject {
        val cases = JSONArray()
        val report = JSONObject().put("scope", "public synthetic Bitmap getPixels/encoding only; no screen, Root or script")
            .put("sdk", Build.VERSION.SDK_INT).put("cases", cases)
        try {
            cases.put(checkAlphaGeometryAndTail())
            for ((width, height) in listOf(720 to 1280, 1280 to 720)) {
                val source = IntArray(width * height) { i -> (0xFF000000.toInt() or (i * 104729 and 0xFFFFFF)) }
                val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
                try {
                    val configurations = listOf(16_384 to 32, 32_768 to 64, 65_536 to 128)
                    val buffers = configurations.map { (pixels, rows) -> RootCaptureTransferBuffer(pixels, rows) }
                    val expected = ByteArrayOutputStream().also { output ->
                        writeRootCapturePixels(width, height, output, buffers[0]) { pixels, y, rows ->
                            bitmap.getPixels(pixels, 0, width, 0, y, width, rows)
                        }
                    }.toByteArray()
                    buffers.forEach { buffer ->
                        val actual = ByteArrayOutputStream()
                        writeRootCapturePixels(width, height, actual, buffer) { pixels, y, rows ->
                            bitmap.getPixels(pixels, 0, width, 0, y, width, rows)
                        }
                        check(expected.contentEquals(actual.toByteArray())) { "READBACK_BYTES_DIFFER" }
                    }
                    val complete = configurations.map { mutableListOf<Long>() }
                    val read = configurations.map { mutableListOf<Long>() }
                    val encode = configurations.map { mutableListOf<Long>() }
                    for (iteration in 0 until 110) for (offset in configurations.indices) {
                        val index = (iteration + offset) % configurations.size
                        val timings = RootCaptureStageTimings()
                        val sink = CountingSink()
                        val started = System.nanoTime()
                        writeRootCapturePixels(width, height, sink, buffers[index], timings) { pixels, y, rows ->
                            bitmap.getPixels(pixels, 0, width, 0, y, width, rows)
                        }
                        val elapsed = (System.nanoTime() - started) / 1_000
                        check(sink.count == expected.size) { "READBACK_SIZE_DIFFERS" }
                        if (iteration >= 10) {
                            complete[index].add(elapsed)
                            read[index].add(timings.pixelReadNs / 1_000)
                            encode[index].add(timings.encodeNs / 1_000)
                        }
                    }
                    for (index in configurations.indices) cases.put(JSONObject()
                        .put("case", "bitmap-${width}x$height-${configurations[index].first}-pixels")
                        .put("pass", true).put("width", width).put("height", height)
                        .put("scratchBytes", buffers[index].pixels.size * 4 + buffers[index].bytes.size)
                        .put("blockRows", buffers[index].prepare(width, height)).put("samples", 100)
                        .put("completeUs", summary(complete[index])).put("pixelReadUs", summary(read[index]))
                        .put("encodeUs", summary(encode[index])))
                } finally { bitmap.recycle() }
            }
            report.put("pass", true)
        } catch (_: Throwable) { report.put("pass", false).put("code", "PUBLIC_BITMAP_READBACK_FAILED") }
        return report
    }

    private fun checkAlphaGeometryAndTail(): JSONObject {
        val buffers = listOf(RootCaptureTransferBuffer(), RootCaptureTransferBuffer(32_768, 64),
            RootCaptureTransferBuffer(65_536, 128))
        for ((width, height) in listOf(65 to 131, 721 to 53, 1 to 35)) {
            val source = IntArray(width * height) { i -> ((i and 255) shl 24) or (i * 104729 and 0xFFFFFF) }
            val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
            try {
                // Compare actual getPixels colors, not the input's pre-quantized translucent colors.
                val colors = IntArray(source.size)
                bitmap.getPixels(colors, 0, width, 0, 0, width, height)
                val expected = ByteArray(source.size * 4)
                encodeRootCapturePixels(colors, expected, colors.size)
                for (buffer in buffers) {
                    val actual = ByteArrayOutputStream()
                    writeRootCapturePixels(width, height, actual, buffer) { pixels, y, rows ->
                        bitmap.getPixels(pixels, 0, width, 0, y, width, rows)
                    }
                    check(expected.contentEquals(actual.toByteArray())) { "ALPHA_GEOMETRY_TAIL_DIFFERS" }
                }
            } finally { bitmap.recycle() }
        }
        return JSONObject().put("case", "bitmap-alpha-geometry-and-tail").put("pass", true)
            .put("geometries", 3).put("configurations", buffers.size)
    }

    private fun summary(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        return JSONObject().put("p50", sorted[49]).put("p95", sorted[94]).put("p99", sorted[98])
    }

    private class CountingSink : OutputStream() {
        var count = 0
        private var observed = 0
        override fun write(value: Int) { count++; observed = observed xor value }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length > 0 && offset + length <= bytes.size)
            count += length
            observed = observed xor bytes[offset].toInt() xor bytes[offset + length - 1].toInt()
        }
    }
}
