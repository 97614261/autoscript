package com.autoscript.studio

import com.autoscript.project.store.ProjectImageCleanup
import com.autoscript.project.store.ProjectImageMask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Integer golden counterpart of pixel-vision/image_processing; never modifies the source. */
internal fun processAdvancedImagePixels(source:IntArray,width:Int,height:Int,operation:ImagePixelOperation,checkpoint:()->Unit):IntArray {
    return operation.mask?.let {applyImageMask(source,width,height,it,checkpoint)}
        ?: applyImageCleanup(source,width,height,operation.mode,requireNotNull(operation.cleanup),checkpoint)
}

internal fun applyImageMask(source:IntArray,width:Int,height:Int,spec:ProjectImageMask,checkpoint:()->Unit):IntArray {
    spec.validate(width,height);require(source.size==width*height)
    val kept=ByteArray(source.size) {1}
    spec.strokes.forEach {stroke ->
        checkpoint()
        val value=if(stroke.keep) 1.toByte() else 0.toByte()
        if(stroke.kind=="rect") {
            val a=stroke.points[0];val b=stroke.points[1]
            for(y in a.y until b.y) {checkpoint();for(x in a.x until b.x) kept[y*width+x]=value}
        } else if(stroke.kind=="polygon") {
            for(y in stroke.points.minOf {it.y} until stroke.points.maxOf {it.y}) {
                checkpoint()
                com.autoscript.project.store.polygonMaskSpans(stroke.points,y,width).forEach {span ->
                    for(x in span) kept[y*width+x]=value
                }
            }
        } else {
            fun dab(x:Int,y:Int) {
                checkpoint();val r=stroke.radius
                for(py in max(0,y-r)..min(height-1,y+r)) for(px in max(0,x-r)..min(width-1,x+r))
                    if((px-x)*(px-x)+(py-y)*(py-y)<=r*r) kept[py*width+px]=value
            }
            dab(stroke.points[0].x,stroke.points[0].y)
            stroke.points.zipWithNext().forEach { (a,b) ->
                var x=a.x;var y=a.y;val dx=abs(b.x-x);val dy=-abs(b.y-y);var error=dx+dy
                val sx=if(x<b.x) 1 else -1;val sy=if(y<b.y) 1 else -1
                while(true) {dab(x,y);if(x==b.x && y==b.y) break;val twice=error*2;if(twice>=dy) {error+=dy;x+=sx};if(twice<=dx) {error+=dx;y+=sy}}
            }
        }
    }
    var visible=false
    val result=IntArray(source.size) { index ->
        if(index and 255==0) checkpoint()
        if(kept[index].toInt()==0) 0 else source[index].also {if(it ushr 24!=0) visible=true}
    }
    require(visible) {"蒙版不能清除全部有效像素"};return result
}

internal fun applyImageCleanup(source:IntArray,width:Int,height:Int,mode:String,spec:ProjectImageCleanup,checkpoint:()->Unit):IntArray {
    spec.validate(mode,width,height);require(source.size==width*height)
    val light=spec.foreground=="light"
    var bits=ByteArray(source.size) { index ->
        if(index and 255==0) checkpoint();val p=source[index]
        val gray=((p ushr 16 and 255)*77+(p ushr 8 and 255)*150+(p and 255)*29+128) ushr 8
        if(p ushr 24!=0 && (gray>=spec.threshold)==light) 1 else 0
    }
    fun morphology(input:ByteArray,dilate:Boolean):ByteArray {
        val output=ByteArray(input.size);val r=spec.radius
        for(y in 0 until height) {checkpoint();for(x in 0 until width) {
            var hit=!dilate
            for(dy in -r..r) for(dx in -r..r) {
                val px=x+dx;val py=y+dy;val value=px in 0 until width && py in 0 until height && input[py*width+px].toInt()!=0
                hit=if(dilate) hit || value else hit && value
            }
            output[y*width+x]=if(hit) 1 else 0
        }}
        return output
    }
    if(mode=="despeckle") {
        val queue=IntArray(bits.size)
        for(start in bits.indices) {
            if(start and 255==0) checkpoint();if(bits[start].toInt()!=1) continue
            var count=1;var read=0;queue[0]=start;bits[start]=2
            while(read<count) {
                if(read and 255==0) checkpoint();val current=queue[read++];val x=current%width;val y=current/width
                for(dy in -1..1) for(dx in -1..1) {
                    if(dx==0 && dy==0 || spec.connectivity==4 && dx!=0 && dy!=0) continue
                    val px=x+dx;val py=y+dy;if(px !in 0 until width || py !in 0 until height) continue
                    val other=py*width+px;if(bits[other].toInt()==1) {bits[other]=2;queue[count++]=other}
                }
            }
            val value=if(count>=spec.minArea) 3.toByte() else 0.toByte()
            for(index in 0 until count) bits[queue[index]]=value
        }
    } else if(mode in setOf("erode","dilate")) bits=morphology(bits,mode=="dilate")
    else {bits=morphology(bits,mode=="close");bits=morphology(bits,mode=="open")}
    return IntArray(source.size) {index ->
        if(index and 255==0) checkpoint();val p=source[index]
        if(p ushr 24==0) 0 else (p and -0x1000000) or if((bits[index].toInt()!=0)==light) 0xFFFFFF else 0
    }
}
