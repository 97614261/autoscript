package com.autoscript.runtime.service

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Public synthetic buffers only; timings include the adapter, not Root or screen capture. */
internal object VisualPerformanceFixture {
    fun run(context: Context): JSONObject {
        val cases = JSONArray()
        val template = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(193)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(template).apply {
            drawColor(Color.WHITE)
            repeat(180) {
                paint.color = Color.rgb(random.nextInt(180), random.nextInt(180), random.nextInt(180))
                drawCircle(random.nextInt(120) + 4f, random.nextInt(120) + 4f, random.nextInt(5) + 1f, paint)
            }
        }
        Canvas(source).apply { drawColor(Color.WHITE); drawBitmap(template, 136f, 136f, null) }
        var id = 9_000_000L
        try {
            NativeVisionAdapter(context.assets).use { adapter ->
                val pixels = rgba(source); val tpl = rgba(template)
                fun sample(name: String, operation: Int, input: ByteBuffer, metadata: IntArray,
                    pattern: ByteBuffer? = null, patternMetadata: IntArray = intArrayOf(), expected: String? = null) {
                    val times = mutableListOf<Long>()
                    var first = 0L
                    var reference: ByteArray? = null
                    repeat(111) { iteration ->
                        val started = SystemClock.elapsedRealtimeNanos()
                        val response = adapter.dispatch(++id, operation, input, metadata, pattern, patternMetadata,
                            intArrayOf(0, 0, metadata[0], metadata[1], 500))
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1000
                        check(response.firstOrNull() == 0.toByte()) { "VISUAL_PERFORMANCE_RESPONSE" }
                        if (reference == null) reference = response else check(reference!!.contentEquals(response)) { "VISUAL_PERFORMANCE_UNSTABLE" }
                        if (iteration == 0) first = elapsed
                        if (iteration >= 11) times.add(elapsed)
                    }
                    times.sort()
                    val result = requireNotNull(reference)
                    cases.put(JSONObject().put("case", name).put("firstCallUs", first).put("modelCold", name == "ocr-v4-1234567890").put("samples", times.size)
                        .put("p50Us", times[49]).put("p95Us", times[94]).put("p99Us", times[98])
                        .put("resultSha256", MessageDigest.getInstance("SHA-256").digest(result).joinToString("") { "%02x".format(it.toInt() and 255) })
                        .apply { if (expected != null) { val actual = String(result, 3, result.size - 3, Charsets.US_ASCII)
                            put("expected", expected); put("actual", actual); put("exact", actual == expected) } })
                }
                sample("gray", 5108, pixels, metadata(source), tpl, metadata(template))
                sample("orb", 5109, pixels, metadata(source), tpl, metadata(template))
                for (text in listOf("1234567890", "A1b2Z9", "Il1O0")) {
                    val font = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; typeface = Typeface.SANS_SERIF }
                    val image = Bitmap.createBitmap(kotlin.math.ceil(font.measureText(text)).toInt() + 24, 56, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(image).apply { drawColor(Color.WHITE); drawText(text, 12f, 39f, font) }
                        sample("ocr-v4-$text", 6101, rgba(image), metadata(image), expected = text)
                    } finally { image.recycle() }
                }
            }
            return JSONObject().put("pass", true).put("cases", cases).put("sdk", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS[0]).put("scope", "synthetic adapter; 1 first call + 10 warmup + 100 hot; only first OCR case is model-cold; no capture/input; V4 unchanged")
        } finally { source.recycle(); template.recycle() }
    }

    private fun metadata(bitmap: Bitmap) = intArrayOf(bitmap.width, bitmap.height, bitmap.width * 4, 1)
    private fun rgba(bitmap: Bitmap): ByteBuffer {
        val colors = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteBuffer.allocateDirect(colors.size * 4).apply {
            colors.forEach { put((it ushr 16).toByte()); put((it ushr 8).toByte()); put(it.toByte()); put((it ushr 24).toByte()) }; flip()
        }
    }
}
