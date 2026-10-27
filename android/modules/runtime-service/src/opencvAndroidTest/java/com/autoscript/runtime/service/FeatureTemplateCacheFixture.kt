package com.autoscript.runtime.service

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import java.nio.ByteBuffer

/** Test APK only: public generated textures, including same-buffer mutation and native ownership. */
class FeatureTemplateCacheFixture {
    fun run():JSONObject {
        val cases=JSONArray()
        fun record(name:String,body:()->Unit) {body();cases.put(JSONObject().put("case",name).put("pass",true))}
        NativeCvBuffers.load()
        val backend=NativeFeatureMatcher()
        val originals=(0..2).map(::texture)
        var id=14_800L
        fun metadata()=intArrayOf(128,128,512,1)
        fun scene(pattern:ByteBuffer,meta:IntArray):ByteBuffer {
            val source=ByteBuffer.allocateDirect(400*400*4).apply {repeat(400*400) {putInt(-1)};flip()}
            for(y in 0 until 128) for(x in 0 until 128) for(c in 0..3)
                source.put(((136+y)*400+136+x)*4+c,pattern.get(y*meta[2]+x*4+c))
            return source
        }
        fun execute(pattern:ByteBuffer,source:ByteBuffer?=null,work:NativeVisionWork=NativeVisionWork(++id),meta:IntArray=metadata())=
            backend.execute(work,source ?: scene(pattern,meta),intArrayOf(400,400,1600,meta[3]),pattern,meta,intArrayOf(0,0,400,400,500))
        val cacheField=NativeFeatureMatcher::class.java.getDeclaredField("templates").apply {isAccessible=true}
        val bytesField=NativeFeatureMatcher::class.java.getDeclaredField("templateBytes").apply {isAccessible=true}
        fun entries()=cacheField.get(backend) as Map<*,*>
        fun descriptors()=entries().values.map {entry -> requireNotNull(entry).javaClass.getDeclaredField("descriptors").apply {isAccessible=true}.get(entry) as Mat}
        fun bounds() {check(entries().size<=2 && bytesField.getLong(backend) in 0..128*1024)}
        try {
            val first=execute(originals[0]);check(first.size==18)
            record("repeat-template-exact-output") {repeat(3) {check(first.contentEquals(execute(originals[0])));bounds()}}
            val retained=descriptors().single()
            record("scene-is-never-cached") {
                val flat=ByteBuffer.allocateDirect(400*400*4).apply {repeat(400*400) {putInt(-1)};flip()}
                check(execute(originals[0],flat).isEmpty())
            }
            record("same-buffer-mutation-changes-key") {
                val copy=ByteBuffer.allocateDirect(originals[0].capacity()).put(originals[0].duplicate()).apply {flip()}
                execute(copy)
                copy.put(originals[1].duplicate()).position(0)
                val changed=execute(copy)
                NativeFeatureMatcher().use {fresh -> check(changed.contentEquals(fresh.execute(NativeVisionWork(++id),scene(copy,metadata()),intArrayOf(400,400,1600,1),copy,metadata(),intArrayOf(0,0,400,400,500))))}
                check(entries().size==2);bounds()
            }
            record("eviction-releases-native-descriptors") {execute(originals[2]);bounds();check(retained.empty())}
            record("transparent-mutation-is-rejected-not-a-cache-hit") {
                val copy=ByteBuffer.allocateDirect(originals[2].capacity()).put(originals[2].duplicate()).apply {flip()}
                copy.put(3,0)
                check(runCatching {execute(copy)}.exceptionOrNull()?.message=="FEATURE_REQUIRES_OPAQUE_TEMPLATE")
                bounds()
            }
            record("warm-hit-cancellation-and-recovery") {
                val cancelled=NativeVisionWork(++id).apply {cancel()}
                check(runCatching {execute(originals[2],work=cancelled)}.isFailure)
                check(execute(originals[2]).size==18);bounds()
            }
            record("format-and-stride-are-part-of-template-identity") {
                val padded=ByteBuffer.allocateDirect(520*128)
                for(y in 0 until 128) for(x in 0 until 128) {
                    val from=(y*128+x)*4;val to=y*520+x*4
                    padded.put(to,originals[2].get(from+2));padded.put(to+1,originals[2].get(from+1))
                    padded.put(to+2,originals[2].get(from));padded.put(to+3,-1)
                }
                val result=execute(padded,meta=intArrayOf(128,128,520,2))
                check(result.contentEquals(execute(originals[2])));bounds()
            }
            val remaining=descriptors()
            record("close-releases-every-retained-descriptor") {backend.close();check(entries().isEmpty());check(bytesField.getLong(backend)==0L);check(remaining.all {it.empty()})}
            return JSONObject().put("pass",true).put("cases",cases).put("scope","synthetic cache identity, cancellation and native release; no capture/input")
        } finally {backend.close()}
    }

    private fun texture(seed:Int):ByteBuffer {
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        try {
            val random=java.util.Random(193L+seed)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            Canvas(bitmap).apply {drawColor(Color.WHITE);repeat(180) {
                paint.color=Color.rgb(random.nextInt(180),random.nextInt(180),random.nextInt(180))
                drawCircle(random.nextInt(120)+4f,random.nextInt(120)+4f,random.nextInt(5)+1f,paint)
            }}
            val colors=IntArray(128*128);bitmap.getPixels(colors,0,128,0,0,128,128)
            return ByteBuffer.allocateDirect(colors.size*4).apply {colors.forEach {
                put((it ushr 16).toByte());put((it ushr 8).toByte());put(it.toByte());put(-1)
            };flip()}
        } finally {bitmap.recycle()}
    }
}
