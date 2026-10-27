package com.autoscript.runtime.service

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Public synthetic pixels only. Plumbing, geometric accuracy and OCR accuracy are distinct results. */
internal object CapabilityVisionAcceptanceFixture {
    fun run(context:Context, source:NativeVisionBackendSource?=null):JSONObject {
        val cases=JSONArray();val timings=JSONArray();var pass=true
        fun record(name:String,block:()->Unit) {try {block();cases.put(JSONObject().put("name",name).put("pass",true))}
            catch(_:Throwable) {pass=false;cases.put(JSONObject().put("name",name).put("pass",false).put("code","SYNTHETIC_VISION_CHECK_FAILED"))}}
        (source?.let { NativeVisionAdapter(it, ownsSource=false) } ?: NativeVisionAdapter(context.assets)).use {adapter ->
            val template=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(template);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                val random=java.util.Random(193)
                repeat(180) {paint.color=Color.rgb(random.nextInt(180),random.nextInt(180),random.nextInt(180));canvas.drawCircle(random.nextInt(120)+4f,random.nextInt(120)+4f,random.nextInt(5)+1f,paint)}
                var work=800_000L
                for((scale,angle) in listOf(1f to 0f,1.4f to 14f)) record("orb-geometry-$scale-$angle") {
                    val source=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
                    try {
                        val matrix=Matrix().apply {postTranslate(-64f,-64f);postScale(scale,scale);postRotate(angle);postTranslate(200f,200f)}
                        Canvas(source).apply {drawColor(Color.WHITE);drawBitmap(template,matrix,Paint(Paint.FILTER_BITMAP_FLAG))}
                        val expected=RectF(0f,0f,128f,128f).also(matrix::mapRect)
                        repeat(3) {
                            val start=SystemClock.elapsedRealtimeNanos()
                            val response=adapter.dispatch(++work,5109,rgba(source),metadata(source),rgba(template),metadata(template),intArrayOf(0,0,400,400,500))
                            timings.put(JSONObject().put("algorithm","orb").put("scale",scale).put("angle",angle).put("elapsedUs",(SystemClock.elapsedRealtimeNanos()-start)/1000))
                            check(response.size==19 && response[0]==0.toByte())
                            val result=ByteBuffer.wrap(response,1,18).order(ByteOrder.LITTLE_ENDIAN)
                            val x=result.int;val y=result.int;val width=result.int;val height=result.int;val score=result.short.toInt() and 65535
                            check(kotlin.math.abs(x-expected.left)<=8 && kotlin.math.abs(y-expected.top)<=8 && kotlin.math.abs(x+width-expected.right)<=8 && kotlin.math.abs(y+height-expected.bottom)<=8 && score>=500)
                        }
                    } finally {source.recycle()}
                }
                record("orb-flat-source-is-not-found") {
                    val source=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
                    try {source.eraseColor(Color.WHITE);val response=adapter.dispatch(++work,5109,rgba(source),metadata(source),rgba(template),metadata(template),intArrayOf(0,0,400,400,500));check(response.contentEquals(byteArrayOf(0)))} finally {source.recycle()}
                }
                val ocr=JSONArray()
                for(expected in listOf("1234567890","ABCDEFG","abcdefg","A1b2Z9","Il1O0")) record("fixed-ocr-pair-$expected") {
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=32f;typeface=Typeface.SANS_SERIF}
                    val source=Bitmap.createBitmap(kotlin.math.ceil(paint.measureText(expected)).toInt()+24,56,Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(source).apply {drawColor(Color.WHITE);drawText(expected,12f,39f,paint)}
                        var original:ByteArray?=null
                        for(opcode in listOf(6101,6104,6101)) {
                            val start=SystemClock.elapsedRealtimeNanos()
                            val response=adapter.dispatch(++work,opcode,rgba(source),metadata(source),null,intArrayOf(),intArrayOf(0,0,source.width,source.height,500))
                            check(response.size>=3 && response[0]==0.toByte())
                            val actual=String(response,3,response.size-3,Charsets.US_ASCII);check(actual.all {it.isLetterOrDigit() && it.code<128})
                            if(opcode==6101) {if(original==null) original=response else check(original.contentEquals(response))}
                            ocr.put(JSONObject().put("opcode",opcode).put("expected",expected).put("actual",actual).put("exact",actual==expected).put("elapsedUs",(SystemClock.elapsedRealtimeNanos()-start)/1000))
                        }
                    } finally {source.recycle()}
                }
                return JSONObject().put("pass",pass).put("scope","synthetic ORB geometry, explicit V5 and unchanged V4; no Root/user images")
                    .put("cases",cases).put("timings",timings).put("ocrSamples",ocr).put("accuracyGate","measurement only; no model replacement")
            } finally {template.recycle()}
        }
    }
    private fun metadata(bitmap:Bitmap)=intArrayOf(bitmap.width,bitmap.height,bitmap.width*4,1)
    private fun rgba(bitmap:Bitmap):ByteBuffer {
        val colors=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return ByteBuffer.allocateDirect(colors.size*4).apply {colors.forEach {put((it ushr 16).toByte());put((it ushr 8).toByte());put(it.toByte());put((it ushr 24).toByte())};flip()}
    }
}
