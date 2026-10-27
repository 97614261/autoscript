package com.autoscript.runtime.service

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.*

/** Previous scalar reference and candidate alternate in the same process; no model or capture. */
internal object OnnxPreprocessingBenchmarkFixture {
    fun run():JSONObject {
        val cases=JSONArray()
        for((w,h) in listOf(214 to 56,113 to 56,320 to 96,4000 to 200)) {
            val stride=(w+4)*4+12
            val bytes=ByteArray(stride*(h+4)).also(java.util.Random(148)::nextBytes)
            val input=ByteBuffer.allocateDirect(bytes.size).put(bytes).apply {flip()}
            val metadata=intArrayOf(w+4,h+4,stride,1);val options=intArrayOf(2,2,w+2,h+2,0)
            val reference=scalar(input,metadata,options)
            val first=prepareOnnxImage(input,metadata,options)
            check(reference.width==first.width && reference.paddedWidth==first.paddedWidth && reference.tensor.contentEquals(first.tensor))
            val times=Array(2) {mutableListOf<Long>()}
            repeat(111) {iteration -> for(variant in listOf(iteration%2,1-iteration%2)) {
                val started=SystemClock.elapsedRealtimeNanos()
                val result=if(variant==0) scalar(input,metadata,options) else prepareOnnxImage(input,metadata,options)
                val elapsed=(SystemClock.elapsedRealtimeNanos()-started)/1000
                check(result.tensor.contentEquals(reference.tensor))
                if(iteration>=11) times[variant].add(elapsed)
            }}
            val entry=JSONObject().put("width",w).put("height",h).put("samplesPerVariant",100)
            for(variant in 0..1) {val values=times[variant].sorted();entry.put(if(variant==0) "scalar" else "prepared",JSONObject()
                .put("p50Us",values[49]).put("p95Us",values[94]).put("p99Us",values[98]))}
            cases.put(entry)
        }
        return JSONObject().put("pass",true).put("cases",cases).put("scope","same-process alternating scalar/prepared; public pixels; allocation included; excludes model and capture")
    }

    private fun scalar(pixels:ByteBuffer,metadata:IntArray,options:IntArray):OnnxImageInput {
        validateVisionBuffer(pixels.capacity(),pixels.remaining(),pixels.isDirect,metadata)
        val roi=checkedVisionRect(metadata[0],metadata[1],options)
        val width=ceil(48.0*roi.width/roi.height).toInt().coerceAtLeast(1);require(width<=960)
        val padded=max(320,width);val tensor=FloatArray(3*48*padded)
        val source=pixels.asReadOnlyBuffer()
        val scaleX=roi.width.toDouble()/width;val scaleY=roi.height.toDouble()/48
        val channels=if(metadata[3]==1) intArrayOf(2,1,0) else intArrayOf(0,1,2)
        val half=roi.width==width*2 && roi.height==96
        fun value(x:Int,y:Int,c:Int)=source.get((roi.y+y.coerceIn(0,roi.height-1))*metadata[2]+(roi.x+x.coerceIn(0,roi.width-1))*4+channels[c]).toInt() and 255
        for(y in 0 until 48) {
            val fy=((y+.5)*scaleY-.5).toFloat();val top=floor(fy).toInt();val fractionY=fy-top
            val wy0=round((1f-fractionY)*2048f).toInt();val wy1=round(fractionY*2048f).toInt()
            for(x in 0 until width) {
                val fx=((x+.5)*scaleX-.5).toFloat();var left=floor(fx).toInt();var fractionX=fx-left
                if(left<0) {left=0;fractionX=0f}
                if(left>=roi.width-1) {left=roi.width-1;fractionX=0f}
                val wx0=round((1f-fractionX)*2048f).toInt();val wx1=round(fractionX*2048f).toInt()
                for(c in 0..2) {
                    val sample=if(half) (value(2*x,2*y,c)+value(2*x+1,2*y,c)+value(2*x,2*y+1,c)+value(2*x+1,2*y+1,c)+2)/4 else {
                        val upper=value(left,top,c)*wx0+value(left+1,top,c)*wx1;val lower=value(left,top+1,c)*wx0+value(left+1,top+1,c)*wx1
                        (((upper ushr 4)*wy0 ushr 16)+((lower ushr 4)*wy1 ushr 16)+2) ushr 2
                    }
                    tensor[c*48*padded+y*padded+x]=sample/127.5f-1f
                }
            }
        }
        return OnnxImageInput(width,padded,tensor)
    }
}
