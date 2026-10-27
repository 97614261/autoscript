package com.autoscript.studio

internal data class ImageDifference(val overlay:IntArray,val compared:Int,val mismatched:Int,val maximumDelta:Int,val averageDelta:Int) {
    val scorePermille:Int get()=(compared-mismatched)*1000/compared
}

/** Candidate-only RGB analysis: transparent template pixels are ignored, never a nearest search. */
internal fun analyzeImageDifference(source:IntArray,template:IntArray,tolerance:Int,checkpoint:()->Unit):ImageDifference {
    require(source.size==template.size && source.size in 1..1_048_576 && tolerance in 0..255)
    val overlay=IntArray(source.size);var compared=0;var mismatched=0;var maximum=0;var total=0L
    for(i in source.indices) {
        if(i and 255==0) checkpoint()
        if(template[i] ushr 24==0) continue
        val red=kotlin.math.abs((source[i] ushr 16 and 255)-(template[i] ushr 16 and 255))
        val green=kotlin.math.abs((source[i] ushr 8 and 255)-(template[i] ushr 8 and 255))
        val blue=kotlin.math.abs((source[i] and 255)-(template[i] and 255))
        val delta=maxOf(red,green,blue);maximum=maxOf(maximum,delta);total+=red+green+blue;compared++
        if(delta>tolerance) {mismatched++;overlay[i]=0xffff4670.toInt()} else overlay[i]=(source[i] and 0x00ffffff) or 0x99000000.toInt()
    }
    checkpoint();require(compared>0) {"模板没有有效像素"}
    return ImageDifference(overlay,compared,mismatched,maximum,(total/(compared.toLong()*3)).toInt())
}
