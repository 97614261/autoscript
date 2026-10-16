package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.ceil

/** Public synthetic inputs only. Diagnostic variants never replace the original accuracy gate. */
internal object OcrDiagnosticFixture {
    fun run(context: Context): JSONObject {
        val cases = JSONArray()
        var work = 1L
        var accurate = true
        NativeVisionAdapter(context.assets).use { adapter ->
            for ((name, font) in listOf("monospace" to Typeface.MONOSPACE, "sans-serif" to Typeface.SANS_SERIF)) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; typeface = font }
                val glyphs = listOf("l", "1").map { glyph ->
                    Bitmap.createBitmap(48, 56, Bitmap.Config.ARGB_8888).apply {
                        Canvas(this).apply { drawColor(Color.WHITE); drawText(glyph, 12f, 39f, paint) }
                    }
                }
                val differentPixels = try {
                    var count = 0
                    for (y in 0 until 56) for (x in 0 until 48) if (glyphs[0].getPixel(x, y) != glyphs[1].getPixel(x, y)) count++
                    count
                } finally { glyphs.forEach(Bitmap::recycle) }
                val expected = "abcdefghijklmnopqrstuvwxyz"
                val image = Bitmap.createBitmap(ceil(paint.measureText(expected)).toInt() + 24, 56, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(image).apply { drawColor(Color.WHITE); drawText(expected, 12f, 39f, paint) }
                    val pixels = ByteBuffer.allocateDirect(image.width * image.height * 4)
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        val rgb = image.getPixel(x, y)
                        pixels.put(Color.red(rgb).toByte()).put(Color.green(rgb).toByte()).put(Color.blue(rgb).toByte()).put(255.toByte())
                    }
                    pixels.flip()
                    val bounds = Rect().also { paint.getTextBounds(expected, 0, expected.length, it) }
                    val rois = listOf(
                        "original" to intArrayOf(0, 0, image.width, image.height, 500),
                        "explicit-tight-roi" to intArrayOf((12 + bounds.left - 2).coerceAtLeast(0), (39 + bounds.top - 2).coerceAtLeast(0),
                            (12 + bounds.right + 2).coerceAtMost(image.width), (39 + bounds.bottom + 2).coerceAtMost(image.height), 500),
                    )
                    for ((variant, roi) in rois) {
                        val result = adapter.dispatch(++work, 6101, pixels, intArrayOf(image.width, image.height, image.width * 4, 1), null, intArrayOf(), roi)
                        val valid = result.size >= 3 && result[0] == 0.toByte()
                        val actual = if (valid) String(result, 3, result.size - 3, Charsets.US_ASCII) else ""
                        val correct = valid && actual == expected
                        accurate = accurate && correct
                        cases.put(JSONObject().put("font", name).put("variant", variant).put("expected", expected).put("actual", actual)
                            .put("roi", JSONArray(roi.toList())).put("differentGlyphPixels", differentPixels).put("pass", correct))
                    }
                } finally { image.recycle() }
            }
        }
        return JSONObject().put("scope", "synthetic OCR font/ROI diagnostics; original gate unchanged")
            .put("cases", cases).put("pass", accurate)
    }
}
