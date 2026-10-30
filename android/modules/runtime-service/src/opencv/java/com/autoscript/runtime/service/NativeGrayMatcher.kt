package com.autoscript.runtime.service

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

/** Optional OpenCV source set. No model/session ownership or ONNX dependency. */
class NativeGrayMatcher : NativeVisionBackend {
    private val features=NativeFeatureMatcher()
    // Keep native calls short enough for the shared cancellation deadline to be observed on
    // slower devices. The total bounded-work guard below remains unchanged for compatibility.
    private companion object {
        const val MAX_TILE_WORK = 8_000_000L
        const val MAX_TEMPLATE_CACHE_ENTRIES = 8
        const val MAX_TEMPLATE_CACHE_BYTES = 4 * 1024 * 1024
    }

    private data class TemplateKey(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val format: Int,
        val digest: String,
    )

    private data class GrayTemplate(val width: Int, val height: Int, val pixels: ByteBuffer) {
        val bytes: Int get() = width * height
    }

    // The provider is serialized by NativeVisionAdapter, but keeping the cache lock local also
    // makes direct backend tests and future provider implementations safe.
    private val templateCache = object : LinkedHashMap<TemplateKey, GrayTemplate>(16, .75f, true) {}
    private var templateCacheBytes = 0
    private val templateCacheLock = Any()

    override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
        if(options.size==6 && options[5]==1) return features.execute(work,pixels,metadata,templatePixels,templateMetadata,options.copyOf(5))
        require(templatePixels != null)
        NativeCvBuffers.load()
        val source = NativeCvBuffers.image(pixels, metadata)
        var template: Mat? = null
        try {
            template = grayTemplate(templatePixels, templateMetadata)
            val roi = checkedVisionRect(metadata[0], metadata[1], options)
            return grayMatch(source, template, Rect(roi.x, roi.y, roi.width, roi.height),
                options[4], work, metadata[3])
        } finally {
            releaseNativeVisionResources({ template?.release() }, { source.release() })
        }
    }

    /**
     * Converts each template to grayscale once per content fingerprint.  The source frame is
     * still converted per request, while repeated find-gray calls in a loop reuse this bounded
     * template representation.  Cache entries own only direct bytes, not an OpenCV Mat handle.
     */
    private fun grayTemplate(templatePixels: ByteBuffer?, metadata: IntArray): Mat {
        require(templatePixels != null)
        val key = templateKey(templatePixels, metadata)
        synchronized(templateCacheLock) {
            templateCache[key]?.let { cached ->
                return Mat(cached.height, cached.width, org.opencv.core.CvType.CV_8UC1,
                    cached.pixels.asReadOnlyBuffer(), cached.width.toLong())
            }
        }

        val input = NativeCvBuffers.image(templatePixels, metadata)
        val gray = Mat()
        try {
            Imgproc.cvtColor(input, gray,
                if (metadata[3] == 1) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGRA2GRAY)
            val byteCount = Math.toIntExact(gray.total())
            val bytes = ByteArray(byteCount)
            gray.get(0, 0, bytes)
            val direct = ByteBuffer.allocateDirect(byteCount)
            direct.put(bytes).apply { flip() }
            val cached = GrayTemplate(metadata[0], metadata[1], direct.asReadOnlyBuffer())
            synchronized(templateCacheLock) {
                templateCache[key]?.let { existing ->
                    return Mat(existing.height, existing.width, org.opencv.core.CvType.CV_8UC1,
                        existing.pixels.asReadOnlyBuffer(), existing.width.toLong())
                }
                templateCache[key] = cached
                templateCacheBytes += cached.bytes
                while (templateCache.size > MAX_TEMPLATE_CACHE_ENTRIES ||
                    templateCacheBytes > MAX_TEMPLATE_CACHE_BYTES) {
                    val iterator = templateCache.entries.iterator()
                    if (!iterator.hasNext()) break
                    val removed = iterator.next().value
                    iterator.remove()
                    templateCacheBytes -= removed.bytes
                }
            }
            return Mat(cached.height, cached.width, org.opencv.core.CvType.CV_8UC1,
                cached.pixels.asReadOnlyBuffer(), cached.width.toLong())
        } finally {
            input.release()
            gray.release()
        }
    }

    private fun templateKey(pixels: ByteBuffer, metadata: IntArray): TemplateKey {
        require(metadata.size >= 4)
        val expected = Math.multiplyExact(metadata[2], metadata[1])
        val input = pixels.asReadOnlyBuffer()
        require(input.isDirect && expected > 0 && input.capacity() >= expected)
        input.position(0)
        input.limit(expected)
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(16 * 1024)
        while (input.hasRemaining()) {
            val count = min(input.remaining(), chunk.size)
            input.get(chunk, 0, count)
            digest.update(chunk, 0, count)
        }
        return TemplateKey(metadata[0], metadata[1], metadata[2], metadata[3],
            hexDigest(digest.digest()))
    }

    /** Avoid Locale/Formatter allocations on every repeated template lookup. */
    private fun hexDigest(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { value ->
                val unsigned = value.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0x0f])
            }
        }
    }

    private fun grayMatch(source: Mat, template: Mat, roi: Rect, threshold: Int, work: NativeVisionWork, sourceFormat: Int): ByteArray {
        require(template.channels() == 1)
        require(template.cols() <= roi.width && template.rows() <= roi.height && template.total() <= 262144)
        val candidates = (roi.width-template.cols()+1).toLong()*(roi.height-template.rows()+1)
        require(candidates*template.total() <= 20_000_000_000L) // Bound work estimate; native FFT optimizations vary by device.
        val src = Mat(); val tpl = template; val result = Mat()
        val clipped = source.submat(roi)
        try {
            Imgproc.cvtColor(clipped, src, if (sourceFormat==1) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGRA2GRAY)
            // matchTemplate only reads the immutable, caller-owned grayscale template.
            val rows = src.rows()-tpl.rows()+1
            val cols = src.cols()-tpl.cols()+1
            val blackTemplate=Core.countNonZero(tpl)==0
            // Bound each native call; avoid thousands of tiny tiles for ordinary 100px templates.
            val tileWidth = min(cols, max(1, (MAX_TILE_WORK/tpl.total()).toInt()))
            val tileRows = max(1, min(32, (MAX_TILE_WORK/(tpl.total()*tileWidth)).toInt()))
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
                    work.checkActive()
                    val match=Core.minMaxLoc(result)
                    val difference=if(blackTemplate) { if(match.minVal<.5) 0.0 else 1.0 } else match.minVal.coerceAtLeast(0.0)
                    val x=left+if(blackTemplate && difference==1.0) 0 else match.minLoc.x.toInt()
                    val y=top+if(blackTemplate && difference==1.0) 0 else match.minLoc.y.toInt()
                    if(difference.isFinite() && (difference<best || (difference==best && (y<by || (y==by && x<bx))))) { best=difference; bx=x; by=y }
                    left+=w
                }
                top+=tileRows
            }
            var score=((1-best).coerceIn(0.0,1.0)*1000).toInt()
            // SQDIFF can leave a tiny residual for identical pixels; keep strict 1000 exact.
            if (threshold == 1000 && score == 999) {
                val candidate = src.submat(Rect(bx, by, tpl.cols(), tpl.rows()))
                try { if (Core.norm(candidate, tpl, Core.NORM_INF) == 0.0) score = 1000 }
                finally { candidate.release() }
            }
            if(!best.isFinite() || score<threshold) return byteArrayOf()
            return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(bx+roi.x).putInt(by+roi.y).putShort(score.toShort()).array()
        } finally { releaseNativeVisionResources({ clipped.release() }, { src.release() }, { result.release() }) }
    }

    override fun close() = synchronized(templateCacheLock) {
        releaseVisionResources({features.close()},{templateCache.clear();templateCacheBytes=0})
    }
}
