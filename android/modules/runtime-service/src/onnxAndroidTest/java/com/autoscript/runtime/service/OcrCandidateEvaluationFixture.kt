package com.autoscript.runtime.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Debug
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Fixed, hash-verified test asset only. This does not make arbitrary models loadable in production. */
class OcrCandidateEvaluationFixture {
    fun run(instrumentation: Instrumentation): JSONObject {
        val reports = JSONArray()
        val report = JSONObject().put("scope", "fixed v4/v5 public synthetic comparison; evaluation completion is not accuracy acceptance")
            .put("productionChanged", false).put("processArch", System.getProperty("os.arch")).put("models", reports)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 150_000
        val environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            val candidate = instrumentation.context.assets.open("candidate/en_PP-OCRv5_rec_mobile.onnx").use { stream ->
                val data = ByteArray(7_872_351)
                var offset = 0
                while (offset < data.size) { val count = stream.read(data, offset, data.size - offset); check(count > 0); offset += count }
                check(stream.read() == -1); data
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(candidate).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(hash == "c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8") { "OCR_CANDIDATE_HASH" }
            for (version in listOf("v4", "v5")) {
                check(SystemClock.elapsedRealtime() < deadline) { "OCR_CANDIDATE_TIMEOUT" }
                val nativeStart = Debug.getNativeHeapAllocatedSize()
                var nativeMaximum = nativeStart
                val row = JSONObject().put("model", version).put("bytes", if (version == "v4") NativeVisionSpi.MODEL_BYTES else candidate.size)
                    .put("sha256", if (version == "v4") NativeVisionSpi.MODEL_SHA256 else hash)
                reports.put(row)
                val timings = mutableListOf<Double>()
                val cases = JSONArray()
                row.put("cases", cases)
                var total = 0; var exact = 0; var work = 40_000L
                NativeVisionAdapter(instrumentation.targetContext.assets).use { baseline ->
                    val initialize = SystemClock.elapsedRealtimeNanos()
                    val session = if (version == "v5") OrtSession.SessionOptions().use { config ->
                        config.setIntraOpNumThreads(1); config.setInterOpNumThreads(1)
                        config.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                        environment.createSession(candidate, config)
                    } else null
                    row.put("sessionCreationMs", if (session == null) JSONObject.NULL else (SystemClock.elapsedRealtimeNanos() - initialize) / 1_000_000.0)
                    try {
                        val dictionary = session?.metadata?.customMetadata?.get("character")?.trimEnd('\n')?.split('\n')?.let { listOf("") + it + " " }
                        if (session != null) {
                            check(dictionary != null && dictionary.size in 63..1024) { "OCR_CANDIDATE_DICTIONARY" }
                            row.put("classes", dictionary.size)
                        }
                        for ((fontName, font) in listOf("monospace" to Typeface.MONOSPACE, "sans-serif" to Typeface.SANS_SERIF)) {
                            for (size in listOf(20, 32, 48)) for (expected in listOf("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789", "Il1O0lI10", "A1b2Z9")) {
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size.toFloat(); typeface = font }
                                val height = ceil(size * 1.75).toInt()
                                val bitmap = Bitmap.createBitmap(ceil(paint.measureText(expected)).toInt() + 24, height, Bitmap.Config.ARGB_8888)
                                try {
                                    Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(expected, 12f, size * 1.21875f, paint) }
                                    val pixels = rgba(bitmap)
                                    val metadata = intArrayOf(bitmap.width, bitmap.height, bitmap.width * 4, 1)
                                    val options = intArrayOf(0, 0, bitmap.width, bitmap.height, 500)
                                    val actuals = linkedSetOf<String>()
                                    var correct = 0
                                    repeat(5) {
                                        check(SystemClock.elapsedRealtime() < deadline) { "OCR_CANDIDATE_TIMEOUT" }
                                        val time = SystemClock.elapsedRealtimeNanos()
                                        val actual = if (session == null) {
                                            val result = baseline.dispatch(++work, 6101, pixels, metadata, null, intArrayOf(), options)
                                            check(result.size >= 3 && result[0] == 0.toByte()) { "OCR_BASELINE_FAILED" }
                                            String(result, 3, result.size - 3, Charsets.US_ASCII)
                                        } else {
                                            val input = prepareOnnxImage(pixels, metadata, options)
                                            OrtSession.RunOptions().use { run ->
                                                val invocation = NativeVisionWork(++work)
                                                val terminate: () -> Unit = { run.setTerminate(true) }
                                                invocation.installCancellation(terminate)
                                                val timeout = timer.schedule({ invocation.cancel() },
                                                    minOf(5000, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                                                try {
                                                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.tensor), longArrayOf(1, 3, 48, input.paddedWidth.toLong())).use { tensor ->
                                                        session.run(mapOf(session.inputNames.single() to tensor), run).use { result ->
                                                            @Suppress("UNCHECKED_CAST") val values = result[0].value as Array<Array<FloatArray>>
                                                            invocation.checkActive()
                                                            check(values.size == 1 && values[0].size <= 1024) { "OCR_CANDIDATE_SHAPE" }
                                                            decode(values[0], requireNotNull(dictionary))
                                                        }
                                                    }
                                                } finally { timeout.cancel(false); invocation.clearCancellation(terminate) }
                                            }
                                        }
                                        val elapsed = (SystemClock.elapsedRealtimeNanos() - time) / 1_000_000.0
                                        if (total == 0) row.put("firstRecognitionMs", elapsed) else timings += elapsed
                                        total++; actuals += actual
                                        if (actual == expected) { exact++; correct++ }
                                    }
                                    cases.put(JSONObject().put("font", fontName).put("fontPx", size).put("expected", expected)
                                        .put("frame", JSONArray(listOf(bitmap.width, bitmap.height))).put("actual", JSONArray(actuals.toList()))
                                        .put("exact", correct).put("samples", 5))
                                    nativeMaximum = maxOf(nativeMaximum, Debug.getNativeHeapAllocatedSize())
                                } finally { bitmap.recycle() }
                            }
                        }
                    } finally { session?.close() }
                }
                val sorted = timings.sorted()
                fun percentile(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
                row.put("samples", total).put("exact", exact).put("warmSamples", sorted.size)
                    .put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95)).put("p99Ms", percentile(.99))
                    .put("nativeStartBytes", nativeStart).put("nativeSampleMaximumBytes", nativeMaximum)
                    .put("nativeAfterCloseBytes", Debug.getNativeHeapAllocatedSize())
            }
            report.put("pass", true) // Completion only; accuracy remains the separately reported exact/sample counts.
        } catch (failure: Throwable) {
            report.put("pass", false).put("error", failure.javaClass.simpleName)
                .put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "OCR_CANDIDATE_FAILED")
        } finally { timer.shutdownNow() }
        return report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
    }

    private fun decode(steps: Array<FloatArray>, dictionary: List<String>): String {
        var previous = -1
        return buildString {
            for (step in steps) {
                check(step.size == dictionary.size && step.all { it.isFinite() && it in 0f..1f }) { "OCR_CANDIDATE_PROBABILITIES" }
                val index = step.indices.maxByOrNull { step[it] } ?: 0
                val symbol = dictionary[index]
                if (index != 0 && index != previous && step[index] >= .5f && symbol.length == 1 &&
                    symbol[0].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) append(symbol)
                previous = index
            }
        }
    }

    private fun rgba(bitmap: Bitmap) = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4).apply {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val rgb = bitmap.getPixel(x, y)
            put(Color.red(rgb).toByte()).put(Color.green(rgb).toByte()).put(Color.blue(rgb).toByte()).put(255.toByte())
        }
        flip()
    }
}
