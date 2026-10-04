package com.autoscript.runtime.service

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Opt-in device test APK only. No Root, injected input, private captures or user projects. */
class VisionAcceptanceInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("displayWidth", targetContext.resources.displayMetrics.widthPixels)
            .put("displayHeight", targetContext.resources.displayMetrics.heightPixels)
            .put("samples", JSONArray()).put("scope", "synthetic adapter test; not end-to-end capture/Input latency")
        val samples = report.getJSONArray("samples")
        val started = SystemClock.elapsedRealtime()
        var id = 100L
        var success = true
        NativeVisionAdapter(targetContext.assets).use { adapter ->
            try {
                val frame = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                val template = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try {
                    frame.eraseColor(Color.WHITE)
                    for (y in 0 until 8) for (x in 0 until 8) {
                        val gray = 20 + ((x * 37 + y * 17) % 180)
                        val color = Color.rgb(gray, gray, gray)
                        template.setPixel(x, y, color); frame.setPixel(31 + x, 23 + y, color)
                    }
                    val source = rgba(frame); val target = rgba(template)
                    val roi = intArrayOf(10, 12, 86, 58, 990)
                    fun match(): ByteArray = adapter.dispatch(++id, 5108, source, metadata(frame), target, metadata(template), roi)
                    val coldStart = SystemClock.elapsedRealtimeNanos()
                    val cold = match()
                    val coldMs = (SystemClock.elapsedRealtimeNanos() - coldStart) / 1_000_000.0
                    val timings = mutableListOf<Double>()
                    repeat(30) {
                        check(SystemClock.elapsedRealtime() - started < 90_000) { "SUITE_TIMEOUT" }
                        val time = SystemClock.elapsedRealtimeNanos(); val result = match()
                        timings.add((SystemClock.elapsedRealtimeNanos() - time) / 1_000_000.0)
                        checkGray(result, 31, 23)
                    }
                    checkGray(cold, 31, 23)
                    samples.put(timing("gray-offset-roi", frame, roi, timings, coldMs).put("template", "8x8").put("pass", true))
                    val missing = adapter.dispatch(++id, 5108, source, metadata(frame), target, metadata(template), intArrayOf(0, 0, 20, 20, 990))
                    check(missing.contentEquals(byteArrayOf(0))) { "GRAY_NOT_FOUND" }
                    adapter.cancel(++id)
                    val cancelled = adapter.dispatch(id, 5108, source, metadata(frame), target, metadata(template), roi)
                    check(error(cancelled) == "VISION_CANCELLED_OR_TIMEOUT")
                    samples.put(JSONObject().put("case", "gray-missing-and-cancel").put("pass", true))
                } finally { frame.recycle(); template.recycle() }

                for (text in listOf("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz", "0123456789", "AaBb09Zz")) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; typeface = Typeface.MONOSPACE }
                    val bitmap = Bitmap.createBitmap(ceil(paint.measureText(text)).toInt() + 24, 56, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(text, 12f, 39f, paint) }
                        val pixels = rgba(bitmap); val roi = intArrayOf(0, 0, bitmap.width, bitmap.height, 500)
                        val timings = mutableListOf<Double>()
                        var exact = 0; var resultText = ""; var coldMs = 0.0
                        repeat(11) { index ->
                            check(SystemClock.elapsedRealtime() - started < 90_000) { "SUITE_TIMEOUT" }
                            val time = SystemClock.elapsedRealtimeNanos()
                            val result = adapter.dispatch(++id, 6101, pixels, metadata(bitmap), null, intArrayOf(), roi)
                            val elapsed = (SystemClock.elapsedRealtimeNanos() - time) / 1_000_000.0
                            check(result.size >= 3 && result[0] == 0.toByte()) { error(result) }
                            resultText = String(result, 3, result.size - 3, Charsets.US_ASCII)
                            if (index == 0) coldMs = elapsed else { timings.add(elapsed); if (resultText == text) exact++ }
                        }
                        samples.put(timing("onnx-alphanumeric", bitmap, roi, timings, coldMs)
                            .put("template", "none").put("expected", text).put("actual", resultText)
                            .put("exactRuns", exact).put("measuredRuns", 10).put("pass", exact == 10))
                        if (exact != 10) success = false // Record accuracy honestly, don't mask poor recognition.
                    } finally { bitmap.recycle() }
                }
                adapter.close()
                val pixels = ByteBuffer.allocateDirect(4).putInt(-1).apply { flip() }
                check(error(adapter.dispatch(++id, 6101, pixels, intArrayOf(1, 1, 4, 1), null, intArrayOf(), intArrayOf(0, 0, 1, 1, 500))) == "VISION_CLOSED")
                samples.put(JSONObject().put("case", "closed-adapter").put("pass", true))
            } catch (failure: Exception) {
                success = false
                // Only public synthetic inputs; no app/user script data in the report.
                report.put("error", failure.javaClass.simpleName + ":" + failure.message.orEmpty().take(160))
            }
        }
        report.put("pass", success)
        val directory = File(targetContext.filesDir, "vision-acceptance").apply { mkdirs() }
        File(directory, "report.json").writeText(report.toString(2))
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", report.toString(2) + "\n")
            putString("report", "files/vision-acceptance/report.json")
        })
    }

    private fun metadata(bitmap: Bitmap) = intArrayOf(bitmap.width, bitmap.height, bitmap.width * 4, 1)
    private fun rgba(bitmap: Bitmap): ByteBuffer {
        val output = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            output.put(Color.red(color).toByte()).put(Color.green(color).toByte()).put(Color.blue(color).toByte()).put(255.toByte())
        }
        output.flip(); return output
    }
    private fun error(result: ByteArray) = if (result.isEmpty()) "EMPTY_REPLY" else String(result, 1, result.size - 1, Charsets.US_ASCII)
    private fun checkGray(result: ByteArray, x: Int, y: Int) {
        check(result.size == 11 && result[0] == 0.toByte()) { error(result) }
        val values = ByteBuffer.wrap(result, 1, 10).order(ByteOrder.LITTLE_ENDIAN)
        check(values.int == x && values.int == y && values.short.toInt() >= 990) { "GRAY_COORDINATES_OR_SCORE" }
    }
    private fun timing(name: String, image: Bitmap, roi: IntArray, values: List<Double>, cold: Double): JSONObject {
        val ordered = values.sorted()
        fun percentile(p: Double) = ordered[(ceil(p * ordered.size).toInt() - 1).coerceIn(0, ordered.lastIndex)]
        return JSONObject().put("case", name).put("frame", "${image.width}x${image.height}")
            .put("roi", JSONArray(roi.toList())).put("coldMs", cold).put("warmSamples", ordered.size)
            .put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95)).put("p99Ms", percentile(.99))
    }
}
