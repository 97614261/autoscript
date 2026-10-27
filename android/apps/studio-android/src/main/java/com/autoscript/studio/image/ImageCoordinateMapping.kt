package com.autoscript.studio

/** Exact inverse sampling to the original image, not proof that a file has a screen origin. */
internal fun ImageProcessingPipeline.mapToOriginal(x:Int,y:Int):Pair<Int,Int> {
    require(x in 0 until dimensions.first && y in 0 until dimensions.second)
    val sizes=mutableListOf(sourceWidth to sourceHeight)
    operations.forEach {sizes+=it.dimensions(sizes.last().first,sizes.last().second)}
    var point=x to y
    for(index in operations.indices.reversed()) {
        val op=operations[index];val (w,h)=sizes[index];val (ow,oh)=sizes[index+1];val (px,py)=point
        point=when(op.mode) {
            "crop"->px+requireNotNull(op.roi).left to py+op.roi.top
            "resize"->(px.toLong()*w/ow).toInt() to (py.toLong()*h/oh).toInt()
            "rotate90"->py to h-1-px;"rotate180"->w-1-px to h-1-py;"rotate270"->w-1-py to px
            "flipX"->w-1-px to py;"flipY"->px to h-1-py
            "gray","threshold","filter","mask","despeckle","erode","dilate","open","close"->point
            else->error("该处理尚无可信逆映射")
        }
    }
    require(point.first in 0 until sourceWidth && point.second in 0 until sourceHeight)
    return point
}
