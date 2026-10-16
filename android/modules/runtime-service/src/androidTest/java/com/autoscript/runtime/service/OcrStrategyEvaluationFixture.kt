package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.ceil

/** Explicit evaluation only. Never selects a strategy, changes decoding, or replaces an accuracy gate. */
internal object OcrStrategyEvaluationFixture {
    fun run(context: Context): JSONObject {
        val rows = JSONArray()
        val report = JSONObject().put("scope", "fixed-model synthetic OCR strategy evaluation; not production acceptance")
            .put("evaluationOnly", true).put("productionChanged", false).put("cases", rows)
            .put("processArch", System.getProperty("os.arch")).put("process64Bit", android.os.Process.is64Bit())
            .put("modelSha256", NativeVisionSpi.MODEL_SHA256)
        val started = SystemClock.elapsedRealtime()
        var workId = 10_000L
        val timings = linkedMapOf<String, MutableList<Double>>()
        val counts = linkedMapOf<String, IntArray>()
        try {
            NativeVisionAdapter(context.assets).use { adapter ->
                val texts = listOf("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789", "Il1O0lI10", "A1b2Z9")
                for ((fontName, font) in listOf("monospace" to Typeface.MONOSPACE, "sans-serif" to Typeface.SANS_SERIF)) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; typeface = font }
                    for (expected in texts) {
                        val original = Bitmap.createBitmap(ceil(paint.measureText(expected)).toInt() + 24, 56, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(original).apply { drawColor(Color.WHITE); drawText(expected, 12f, 39f, paint) }
                            val textBounds = Rect().also { paint.getTextBounds(expected, 0, expected.length, it) }
                            val crop = Rect((12 + textBounds.left - 2).coerceAtLeast(0), (39 + textBounds.top - 2).coerceAtLeast(0),
                                (12 + textBounds.right + 2).coerceAtMost(original.width), (39 + textBounds.bottom + 2).coerceAtMost(original.height))
                            for (variant in listOf("original", "tight-roi", "white-border", "scale-2x")) {
                                check(SystemClock.elapsedRealtime() - started < 90_000) { "OCR_EVALUATION_TIMEOUT" }
                                val image = when (variant) {
                                    "white-border" -> Bitmap.createBitmap(original.width + 32, original.height + 32, Bitmap.Config.ARGB_8888).apply {
                                        Canvas(this).apply { drawColor(Color.WHITE); drawBitmap(original, 16f, 16f, null) }
                                    }
                                    "scale-2x" -> Bitmap.createScaledBitmap(original, original.width * 2, original.height * 2, true)
                                    else -> original
                                }
                                try {
                                    val roi = if (variant == "tight-roi") crop else Rect(0, 0, image.width, image.height)
                                    val pixels = rgba(image)
                                    val samples = JSONArray()
                                    repeat(3) { repeat ->
                                        check(SystemClock.elapsedRealtime() - started < 90_000) { "OCR_EVALUATION_TIMEOUT" }
                                        val start = SystemClock.elapsedRealtimeNanos()
                                        val result = adapter.dispatch(++workId, 6101, pixels,
                                            intArrayOf(image.width, image.height, image.width * 4, 1), null, intArrayOf(),
                                            intArrayOf(roi.left, roi.top, roi.right, roi.bottom, 500))
                                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                                        check(result.size >= 3 && result[0] == 0.toByte()) { "OCR_EVALUATION_OPERATION_FAILED" }
                                        val actual = String(result, 3, result.size - 3, Charsets.US_ASCII)
                                        val exact = actual == expected
                                        val cold = workId == 10_001L
                                        if (!cold) timings.getOrPut(variant) { mutableListOf() }.add(elapsed)
                                        counts.getOrPut(variant) { intArrayOf(0, 0) }.also { it[0]++; if (exact) it[1]++ }
                                        samples.put(JSONObject().put("repeat", repeat).put("actual", actual).put("exact", exact)
                                            .put("cold", cold).put("elapsedMs", elapsed))
                                    }
                                    rows.put(JSONObject().put("font", fontName).put("expected", expected).put("variant", variant)
                                        .put("frame", JSONArray(listOf(image.width, image.height)))
                                        .put("roi", JSONArray(listOf(roi.left, roi.top, roi.right, roi.bottom)))
                                        .put("thresholdPermille", 500).put("samples", samples))
                                } finally { if (image !== original) image.recycle() }
                            }
                        } finally { original.recycle() }
                    }
                }
            }
            report.put("summary", JSONArray(counts.map { (variant, count) ->
                val sorted = timings.getValue(variant).sorted()
                fun percentile(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
                JSONObject().put("variant", variant).put("samples", count[0]).put("exact", count[1])
                    .put("warmSamples", sorted.size).put("p50Ms", percentile(.50)).put("p95Ms", percentile(.95)).put("p99Ms", percentile(.99))
            })).put("allExact", counts.values.all { it[0] == it[1] }).put("pass", true)
        } catch (failure: Throwable) {
            report.put("pass", false).put("error", failure.javaClass.simpleName)
                .put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "OCR_EVALUATION_FAILED")
        }
        return report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
    }

    private fun rgba(image: Bitmap): ByteBuffer = ByteBuffer.allocateDirect(image.width * image.height * 4).apply {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image.getPixel(x, y)
            put(Color.red(color).toByte()).put(Color.green(color).toByte()).put(Color.blue(color).toByte()).put(255.toByte())
        }
        flip()
    }
}
