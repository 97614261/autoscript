package com.autoscript.studio

import com.autoscript.project.store.ProjectMaskPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Pixel samples and half-open region boundaries have different inverse transforms. */
internal fun ImageProcessingPipeline.mapBoundaryToOriginal(x: Double, y: Double): Pair<Double, Double> {
    require(x.isFinite() && y.isFinite() && x in 0.0..dimensions.first.toDouble() && y in 0.0..dimensions.second.toDouble())
    val sizes = mutableListOf(sourceWidth to sourceHeight)
    operations.forEach { sizes += it.dimensions(sizes.last().first, sizes.last().second) }
    var px=x;var py=y
    for (index in operations.indices.reversed()) {
        val op=operations[index];val (w,h)=sizes[index];val (ow,oh)=sizes[index+1]
        val next=when(op.mode) {
            "crop" -> px+requireNotNull(op.roi).left to py+op.roi.top
            "resize" -> px*w/ow to py*h/oh
            "rotate90" -> py to h-px
            "rotate180" -> w-px to h-py
            "rotate270" -> w-py to px
            "flipX" -> w-px to py
            "flipY" -> px to h-py
            "gray","threshold","filter","mask","despeckle","erode","dilate","open","close" -> px to py
            else -> error("该处理尚无可信边界映射")
        }
        px=next.first;py=next.second
    }
    return px.coerceIn(0.0,sourceWidth.toDouble()) to py.coerceIn(0.0,sourceHeight.toDouble())
}

internal fun ImageProcessingPipeline.selectionToOriginal(selection: ImageToolSelection): ImageToolSelection {
    val polygon=selection.polygon.map {p ->
        val xy=mapBoundaryToOriginal(p.x.toDouble(),p.y.toDouble())
        ProjectMaskPoint(xy.first.roundToInt(),xy.second.roundToInt())
    }.also { if(it.isNotEmpty()) ImageSearchPolygon.validate(it) }
    val box=selection.box?.let {roi ->
        require(roi.left < roi.right && roi.top < roi.bottom) { "选区需要有效面积" }
        val corners=listOf(mapBoundaryToOriginal(roi.left.toDouble(),roi.top.toDouble()),
            mapBoundaryToOriginal(roi.right.toDouble(),roi.top.toDouble()),
            mapBoundaryToOriginal(roi.left.toDouble(),roi.bottom.toDouble()),
            mapBoundaryToOriginal(roi.right.toDouble(),roi.bottom.toDouble()))
        ImageToolCodeGen.Roi(floor(corners.minOf {it.first}).toInt(),floor(corners.minOf {it.second}).toInt(),
            ceil(corners.maxOf {it.first}).toInt(),ceil(corners.maxOf {it.second}).toInt())
    }
    val points=selection.points.map {p -> val xy=mapToOriginal(p.x,p.y);p.copy(x=xy.first,y=xy.second) }
    return selection.copy(box=box,polygon=polygon,points=points)
}
