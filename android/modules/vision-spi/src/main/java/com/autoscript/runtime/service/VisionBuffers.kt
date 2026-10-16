package com.autoscript.runtime.service

fun validateVisionBuffer(capacity: Int, remaining: Int, direct: Boolean, metadata: IntArray) {
    require(metadata.size == 4 && metadata[0] in 1..16384 && metadata[1] in 1..16384 && metadata[3] in 1..2)
    val stride = metadata[2].toLong()
    val required = stride * metadata[1]
    require(stride >= metadata[0].toLong() * 4 && required in 1..(64L * 1024 * 1024) &&
        direct && capacity.toLong() == required && remaining == capacity)
}

/** Attempt every owned release, then propagate the first failure to the typed adapter boundary. */
fun releaseVisionResources(vararg releases: () -> Unit) {
    var failure: Throwable? = null
    releases.forEach { release ->
        try { release() } catch (problem: Throwable) { if (failure == null) failure = problem }
    }
    failure?.let { throw it }
}

data class VisionRect(val x: Int, val y: Int, val width: Int, val height: Int)

fun checkedVisionRect(width: Int, height: Int, options: IntArray): VisionRect {
    require(options.size == 5)
    val (left, top, right, bottom) = options
    require(left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height)
    require((right - left).toLong() * (bottom - top) <= 4_194_304)
    return VisionRect(left, top, right - left, bottom - top)
}

fun decodeAlphanumeric(steps: Array<FloatArray>, dictionary: List<String>, threshold: Int): Pair<String,Int> {
    require(threshold in 0..1000 && steps.size<=1024 && dictionary.size in 63..256)
    val text=StringBuilder(); var previous=-1; var score=0.0; var count=0
    for(step in steps) {
        require(step.size==dictionary.size && step.all { it.isFinite() && it in 0f..1f })
        val index=step.indices.maxByOrNull { step[it] } ?: 0
        val symbol=dictionary[index]
        if(index!=0 && index!=previous && step[index]*1000>=threshold && symbol.length==1 && symbol[0].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) {
            text.append(symbol); score+=step[index]; count++
        }
        previous=index
    }
    return text.toString() to if(count==0) 0 else (score/count*1000).toInt().coerceIn(0,1000)
}
