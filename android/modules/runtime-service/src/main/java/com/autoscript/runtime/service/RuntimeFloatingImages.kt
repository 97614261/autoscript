package com.autoscript.runtime.service

import android.graphics.Bitmap

internal class RuntimeFloatingImages {
    private data class Raw(val width:Int,val height:Int,val bytes:ByteArray)
    private val gate=Any()
    private var epoch=0L
    private var enabled=false
    private val raw=mutableMapOf<String,Raw>()
    private val rejected=mutableSetOf<String>()
    private val bitmaps=mutableMapOf<String,Bitmap>()
    private var rawBytes=0L
    private var bitmapBytes=0L
    fun ticket():Long=synchronized(gate) {epoch}
    fun configure(value:Boolean)=synchronized(gate) {epoch++; enabled=value; raw.clear(); rejected.clear(); bitmaps.clear(); rawBytes=0; bitmapBytes=0}
    fun register(ticket:Long,path:String,width:Int,height:Int,rgba:ByteArray)=synchronized(gate) {
        if(!enabled || epoch!=ticket) return@synchronized
        if(path in raw || path in rejected) return@synchronized
        if(width<=0 || height<=0 || width.toLong()*height>4_194_304 || rgba.size.toLong()!=width.toLong()*height*4 || raw.size+rejected.size>=256 || rawBytes+rgba.size>64L*1024*1024) {if(rejected.size<256) rejected.add(path); return@synchronized}
        raw[path]=Raw(width,height,rgba); rawBytes+=rgba.size
    }
    fun bitmap(path:String):Bitmap=synchronized(gate) {
        check(enabled)
        bitmaps[path]?.let {return@synchronized it}
        require(path !in rejected) {"floating image exceeds cache budget"}
        val image=raw[path] ?: error("floating image not registered")
        require(bitmapBytes+image.bytes.size<=32L*1024*1024) {"floating decoded images exceed 32MiB"}
        val pixels=IntArray(image.width*image.height) {index ->
            val offset=index*4; val bytes=image.bytes
            ((bytes[offset+3].toInt() and 255) shl 24) or ((bytes[offset].toInt() and 255) shl 16) or ((bytes[offset+1].toInt() and 255) shl 8) or (bytes[offset+2].toInt() and 255)
        }
        Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888).also {bitmaps[path]=it; bitmapBytes+=image.bytes.size}
    }
    fun releaseDecoded()=synchronized(gate) {bitmaps.clear(); bitmapBytes=0}
}
