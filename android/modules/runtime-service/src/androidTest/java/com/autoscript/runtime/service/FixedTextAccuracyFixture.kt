package com.autoscript.runtime.service

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import com.autoscript.engine.jni.NativeEngineBridge
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Fixed public training alphabet, held-out sequences. Expected text is used only after recognition. */
internal object FixedTextAccuracyFixture {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val texts = listOf("1234567890", "ABCDEFG", "abcdefg", "A1b2Z9", "Il1O0lI10")
    private data class Glyph(val label: Char, val width: Int, val height: Int, val bits: ByteArray)

    fun run(context: Context): JSONObject {
        val rows = JSONArray()
        val report = JSONObject().put("scope", "public fixed-font/size/color corpus; glyph training is font-specific; no model/default replacement")
            .put("cases", rows).put("modelV4", NativeVisionSpi.MODEL_SHA256).put("modelV5", NativeVisionSpi.V5_SHA256)
            .put("glyphSimilarityPermille",900).put("foregroundTolerance",96).put("modelConfidencePermille",500)
            .put("calibration","public alphabet rows per font, size and polarity; test words never select dictionary entries")
        val started = SystemClock.elapsedRealtime()
        val timings = linkedMapOf<String, MutableList<Long>>()
        var id = 920_000L
        var ambiguousShapes = 0
        try {
            NativeVisionAdapter(context.assets).use { adapter ->
                for ((fontName, font) in listOf("sans-serif" to Typeface.SANS_SERIF, "monospace" to Typeface.MONOSPACE)) {
                    for (size in listOf(20, 32, 44)) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=font; textSize=size.toFloat(); color=Color.BLACK }
                        // The authoring tool samples a whole calibration row, preserving its
                        // common top/bottom. Isolated tight glyph crops are not equivalent.
                        val calibration=listOf("0123456789","ABCDEFGHIJKLMNOPQRSTUVWXYZ","abcdefghijklmnopqrstuvwxyz",
                            ALPHABET,"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefhiklmnorstuvwxz")
                        val training=listOf(false,true).associateWith { dark ->
                            val dictionaryStarted=SystemClock.elapsedRealtimeNanos()
                            val glyphs=calibration.flatMap { row ->
                                val image=render(row,paint,dark)
                                try { sampleRow(row,image,paint,dark) } finally {image.recycle()}
                            }.distinctBy { "${it.label}:${it.width}:${it.height}:${it.bits.joinToString(",")}" }
                            check(glyphs.size<=256) { "FIXED_DICTIONARY_BUDGET" }
                            ambiguousShapes += glyphs.indices.count { a -> glyphs.take(a).any {
                                it.label!=glyphs[a].label && it.width==glyphs[a].width && it.height==glyphs[a].height && it.bits.contentEquals(glyphs[a].bits)
                            } }
                            Triple(encode(glyphs),(SystemClock.elapsedRealtimeNanos()-dictionaryStarted)/1000,glyphs.size)
                        }
                        for (dark in listOf(false,true)) for (expected in texts) {
                            val (dictionary,setupUs,glyphCount)=training.getValue(dark)
                            check(SystemClock.elapsedRealtime()-started < 120_000) { "FIXED_TEXT_TIMEOUT" }
                            val image=render(expected,paint,dark)
                            try {
                                val pixels=rgba(image)
                                for (algorithm in listOf("glyph", "v4", "v5")) {
                                    val begin=SystemClock.elapsedRealtimeNanos()
                                    val actual = if (algorithm=="glyph") {
                                        val bytes=ByteArray(pixels.capacity()).also { pixels.duplicate().get(it) }
                                        val response=JSONObject(requireNotNull(NativeEngineBridge.nativeTestGlyph(++id,image.width,image.height,bytes,dictionary,
                                            intArrayOf(0,0,image.width,image.height,900,if(dark) 0xffffff else 0,96,0))))
                                        check(response.getInt("status") in 0..1) { "FIXED_GLYPH_FAILED" }
                                        response.getString("text")
                                    } else {
                                        val response=adapter.dispatch(++id,if(algorithm=="v4") 6101 else 6104,pixels,
                                            intArrayOf(image.width,image.height,image.width*4,1),null,intArrayOf(),intArrayOf(0,0,image.width,image.height,500))
                                        check(response.size>=3 && response[0]==0.toByte()) { "FIXED_MODEL_FAILED" }
                                        String(response,3,response.size-3,Charsets.US_ASCII)
                                    }
                                    val elapsed=(SystemClock.elapsedRealtimeNanos()-begin)/1000
                                    timings.getOrPut(algorithm) { mutableListOf() }.add(elapsed)
                                    rows.put(JSONObject().put("font",fontName).put("sizePx",size).put("background",if(dark) "dark" else "light")
                                        .put("algorithm",algorithm).put("expected",expected).put("actual",actual).put("exact",actual==expected)
                                        .put("editDistance",distance(expected,actual)).put("elapsedUs",elapsed).put("dictionarySetupUs",setupUs)
                                        .put("dictionaryVariants",glyphCount)
                                        .put("frame",JSONArray(listOf(image.width,image.height))))
                                }
                            } finally { image.recycle() }
                        }
                    }
                }
            }
            val allRows=(0 until rows.length()).map { rows.getJSONObject(it) }
            report.put("summary",JSONArray(timings.map { (algorithm, values) ->
                val samples=allRows.filter { it.getString("algorithm")==algorithm }
                val warm=values.drop(1).sorted()
                fun p(f:Double)=warm[(ceil(warm.size*f).toInt()-1).coerceIn(0,warm.lastIndex)]
                JSONObject().put("algorithm",algorithm).put("samples",samples.size).put("exact",samples.count { it.getBoolean("exact") })
                    .put("editDistance",samples.sumOf { it.getInt("editDistance") }).put("expectedCharacters",samples.sumOf { it.getString("expected").length })
                    .put("firstCallUs",values.first()).put("warmSamples",warm.size).put("p50Us",p(.5)).put("p95Us",p(.95)).put("p99Us",p(.99))
            })).put("ambiguousTrainingShapes",ambiguousShapes).put("pass",true).put("allExact",allRows.all { it.getBoolean("exact") })
        } catch (failure:Throwable) {
            report.put("pass",false).put("code",failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "FIXED_TEXT_EVALUATION_FAILED")
        }
        return report.put("elapsedMs",SystemClock.elapsedRealtime()-started)
    }

    private fun render(text:String, paint:Paint, dark:Boolean):Bitmap {
        val widths=text.map { ceil(paint.measureText(it.toString())).toInt()+4 }
        val image=Bitmap.createBitmap(widths.sum()+16,paint.textSize.toInt()+24,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(if(dark) Color.BLACK else Color.WHITE)
        paint.color=if(dark) Color.WHITE else Color.BLACK
        var x=8
        text.forEachIndexed { index,c -> canvas.drawText(c.toString(),x.toFloat(),paint.textSize+6,paint);x+=widths[index] }
        return image
    }
    private fun sampleRow(text:String,image:Bitmap,paint:Paint,dark:Boolean):List<Glyph> {
        val points=mutableListOf<Pair<Int,Int>>()
        for(y in 0 until image.height) for(x in 0 until image.width) {
            val value=Color.red(image.getPixel(x,y))
            if(if(dark) value>=159 else value<=96) points+=x to y
        }
        check(points.isNotEmpty())
        val top=points.minOf {it.second};val h=points.maxOf {it.second}-top+1
        var cellLeft=8
        return text.map { label ->
            val cellRight=cellLeft+ceil(paint.measureText(label.toString())).toInt()+4
            val members=points.filter {it.first in cellLeft until cellRight}
            val left=members.minOf {it.first};val w=members.maxOf {it.first}-left+1
            val bits=ByteArray((w*h+7)/8)
            members.forEach { (x,y) -> val i=(y-top)*w+x-left;bits[i/8]=(bits[i/8].toInt() or (128 ushr (i%8))).toByte() }
            cellLeft=cellRight
            Glyph(label,w,h,bits)
        }
    }
    private fun encode(glyphs:List<Glyph>):ByteArray = ByteBuffer.allocate(16+glyphs.sumOf {13+it.bits.size}).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(byteArrayOf(65,83,71,76,89,80,72,0));putShort(1);putShort(0);putInt(glyphs.size)
        glyphs.forEach { putShort(1);putShort(it.width.toShort());putShort(it.height.toShort());putShort(0);putInt(it.bits.size);put(it.label.code.toByte());put(it.bits) }
    }.array()
    private fun rgba(image:Bitmap):ByteBuffer = ByteBuffer.allocateDirect(image.width*image.height*4).apply {
        for(y in 0 until image.height) for(x in 0 until image.width) {val c=image.getPixel(x,y);put(Color.red(c).toByte());put(Color.green(c).toByte());put(Color.blue(c).toByte());put(255.toByte())};flip()
    }
    private fun distance(a:String,b:String):Int {
        var previous=IntArray(b.length+1) {it}
        a.forEachIndexed { i,c -> val next=IntArray(b.length+1);next[0]=i+1
            b.forEachIndexed { j,d -> next[j+1]=minOf(next[j]+1,previous[j+1]+1,previous[j]+if(c==d) 0 else 1) };previous=next }
        return previous.last()
    }
}
