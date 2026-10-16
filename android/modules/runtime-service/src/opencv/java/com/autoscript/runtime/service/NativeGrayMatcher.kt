package com.autoscript.runtime.service

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Optional OpenCV source set. No model/session ownership or ONNX dependency. */
class NativeGrayMatcher : NativeVisionBackend {
    override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
        require(templatePixels != null)
        NativeCvBuffers.load()
        val source = NativeCvBuffers.image(pixels, metadata)
        var template: Mat? = null
        try {
            template = NativeCvBuffers.image(templatePixels, templateMetadata)
            val roi = checkedVisionRect(metadata[0], metadata[1], options)
            return grayMatch(source, template, Rect(roi.x, roi.y, roi.width, roi.height),
                options[4], work, metadata[3], templateMetadata[3])
        } finally {
            releaseNativeVisionResources({ template?.release() }, { source.release() })
        }
    }

    private fun grayMatch(source: Mat, template: Mat, roi: Rect, threshold: Int, work: NativeVisionWork, sourceFormat: Int, templateFormat: Int): ByteArray {
        require(template.cols() <= roi.width && template.rows() <= roi.height && template.total() <= 262144)
        val candidates = (roi.width-template.cols()+1).toLong()*(roi.height-template.rows()+1)
        require(candidates*template.total() <= 20_000_000_000L) // Bound work estimate; native FFT optimizations vary by device.
        val src = Mat(); val tpl = Mat(); val result = Mat()
        val clipped = source.submat(roi)
        try {
            Imgproc.cvtColor(clipped, src, if (sourceFormat==1) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGRA2GRAY)
            Imgproc.cvtColor(template, tpl, if (templateFormat==1) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGRA2GRAY)
            val rows = src.rows()-tpl.rows()+1
            val cols = src.cols()-tpl.cols()+1
            val blackTemplate=Core.countNonZero(tpl)==0
            // Bound each native call; avoid thousands of tiny tiles for ordinary 100px templates.
            val tileWidth = min(cols, max(1, (32_000_000L/tpl.total()).toInt()))
            val tileRows = max(1, min(32, (32_000_000L/(tpl.total()*tileWidth)).toInt()))
            var best = Double.POSITIVE_INFINITY; var bx=0; var by=0
            var top=0
            while(top<rows) {
                var left=0
                while(left<cols) {
                    work.checkActive()
                    val w=min(tileWidth,cols-left); val h=min(tileRows,rows-top)
                    val tile=src.submat(Rect(left,top,w+tpl.cols()-1,h+tpl.rows()-1))
                    // Normed SQDIFF is undefined for an all-black template. Exact black gets 1,
                    // every non-black region gets 0, preserving the limit of our stated score.
                    try { Imgproc.matchTemplate(tile,tpl,result,if(blackTemplate) Imgproc.TM_SQDIFF else Imgproc.TM_SQDIFF_NORMED) } finally { tile.release() }
                    val match=Core.minMaxLoc(result)
                    val difference=if(blackTemplate) { if(match.minVal<.5) 0.0 else 1.0 } else match.minVal.coerceAtLeast(0.0)
                    val x=left+if(blackTemplate && difference==1.0) 0 else match.minLoc.x.toInt()
                    val y=top+if(blackTemplate && difference==1.0) 0 else match.minLoc.y.toInt()
                    if(difference.isFinite() && (difference<best || (difference==best && (y<by || (y==by && x<bx))))) { best=difference; bx=x; by=y }
                    left+=w
                }
                top+=tileRows
            }
            val score=((1-best).coerceIn(0.0,1.0)*1000).toInt()
            if(!best.isFinite() || score<threshold) return byteArrayOf()
            return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(bx+roi.x).putInt(by+roi.y).putShort(score.toShort()).array()
        } finally { releaseNativeVisionResources({ clipped.release() }, { src.release() }, { tpl.release() }, { result.release() }) }
    }

    override fun close() = Unit
}
