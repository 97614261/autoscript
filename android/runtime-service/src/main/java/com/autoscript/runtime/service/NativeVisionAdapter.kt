package com.autoscript.runtime.service

import android.content.res.AssetManager
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Fixed, synchronous JNI adapter. Input buffers are borrowed and never retained. */
internal class NativeVisionAdapter(private val assets: AssetManager) : AutoCloseable {
    private class Work(val id: Long) {
        val cancelled = AtomicBoolean(false)
        val options = AtomicReference<OrtSession.RunOptions?>(null)
        fun cancel() { cancelled.set(true); options.get()?.let { runCatching { it.setTerminate(true) } } }
    }
    private val active = AtomicReference<Work?>(null)
    private val cancelled = LinkedHashSet<Long>()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "vision-deadline").apply { isDaemon = true } }
    private var session: OrtSession? = null
    private val cvLoaded by lazy { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); Core.setNumThreads(1); true }

    @Suppress("unused") // Called only by the fixed engine JNI adapter.
    fun cancel(id: Long) {
        synchronized(cancelled) {
            cancelled.add(id)
            while (cancelled.size > 64) cancelled.remove(cancelled.first())
        }
        active.get()?.takeIf { it.id == id }?.cancel()
    }

    @Suppress("unused")
    fun dispatch(id: Long, operation: Int, pixels: ByteBuffer, metadata: IntArray,
                 templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
        val work = Work(id)
        if (!active.compareAndSet(null, work)) return failure("VISION_BUSY")
        val expiry = deadlines.schedule({ work.cancel() }, 5, TimeUnit.SECONDS)
        var source: Mat? = null
        var template: Mat? = null
        return try {
            synchronized(cancelled) { if (cancelled.remove(id)) work.cancel() }
            checkWork(work)
            require(cvLoaded && options.size == 5)
            source = image(pixels, metadata)
            val roi = checkedVisionRect(metadata[0], metadata[1], options)
            val score = options[4].also { require(it in 0..1000) }
            val payload = when (operation) {
                5108 -> {
                    require(templatePixels != null)
                    template = image(templatePixels, templateMetadata)
                    grayMatch(source, template, roi, score, work, metadata[3], templateMetadata[3])
                }
                6101 -> recognize(source, roi, score, work, metadata[3])
                else -> error("VISION_OPERATION_INVALID")
            }
            checkWork(work)
            byteArrayOf(0) + payload
        } catch (_: UnsatisfiedLinkError) { failure("VISION_LIBRARY_UNAVAILABLE")
        } catch (_: Throwable) {
            failure(if (work.cancelled.get()) "VISION_CANCELLED_OR_TIMEOUT" else "VISION_OPERATION_FAILED")
        } finally {
            template?.release(); source?.release()
            expiry.cancel(false)
            active.compareAndSet(work, null)
            synchronized(cancelled) { cancelled.remove(id) }
        }
    }

    private fun image(pixels: ByteBuffer, meta: IntArray): Mat {
        require(meta.size == 4 && meta[0] in 1..16384 && meta[1] in 1..16384 && meta[3] in 1..2)
        val required = meta[2].toLong() * meta[1]
        require(meta[2].toLong() >= meta[0].toLong()*4 && required in 1..(64L*1024*1024) && pixels.isDirect && pixels.capacity().toLong() == required)
        return Mat(meta[1], meta[0], CvType.CV_8UC4, pixels.asReadOnlyBuffer(), meta[2].toLong())
    }

    private fun grayMatch(source: Mat, template: Mat, roi: Rect, threshold: Int, work: Work, sourceFormat: Int, templateFormat: Int): ByteArray {
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
                    checkWork(work)
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
        } finally { clipped.release(); src.release(); tpl.release(); result.release() }
    }

    private fun model(): OrtSession {
        session?.let { return it }
        val bytes=assets.open("vision/en_PP-OCRv4_rec_mobile.onnx").use { stream ->
            val data=ByteArray(7_653_044)
            var offset=0
            while(offset<data.size) { val count=stream.read(data,offset,data.size-offset); require(count>0); offset+=count }
            require(stream.read()==-1); data
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash=="e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318")
        val environment=OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        OrtSession.SessionOptions().use { config ->
            config.setIntraOpNumThreads(1); config.setInterOpNumThreads(1)
            config.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            return environment.createSession(bytes,config).also { session=it }
        }
    }

    private fun recognize(source: Mat, roi: Rect, threshold: Int, work: Work, format: Int): ByteArray {
        val width=ceil(48.0*roi.width/roi.height).toInt().coerceAtLeast(1)
        require(width<=960)
        val padded=max(320,width)
        val input=FloatArray(3*48*padded)
        val crop=source.submat(roi); val rgb=Mat(); val resized=Mat()
        try {
            // PaddleOCR recognition consumes OpenCV BGR, not display RGB.
            Imgproc.cvtColor(crop,rgb,if(format==1) Imgproc.COLOR_RGBA2BGR else Imgproc.COLOR_BGRA2BGR)
            Imgproc.resize(rgb,resized,Size(width.toDouble(),48.0),0.0,0.0,Imgproc.INTER_LINEAR)
            val data=ByteArray(48*width*3); resized.get(0,0,data)
            for(y in 0 until 48) for(x in 0 until width) for(c in 0..2) input[c*48*padded+y*padded+x]=((data[(y*width+x)*3+c].toInt() and 255)/127.5f)-1f
        } finally { crop.release(); rgb.release(); resized.release() }
        checkWork(work)
        val inference=model()
        checkWork(work)
        OrtSession.RunOptions().use { run ->
            work.options.set(run)
            try {
                checkWork(work)
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input),longArrayOf(1,3,48,padded.toLong())).use { tensor ->
                    inference.run(mapOf(inference.inputNames.single() to tensor),run).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val values=result[0].value as Array<Array<FloatArray>>
                        require(values.size==1 && values[0].size<=1024)
                        val dictionary=inference.metadata.customMetadata["character"]?.trimEnd('\n')?.split('\n') ?: error("OCR_DICTIONARY_MISSING")
                        // This fixed PP-OCRv4 model has 97 classes: blank + metadata + appended space.
                        val decoded=decodeAlphanumeric(values[0],listOf("")+dictionary+listOf(" "),threshold)
                        return ByteBuffer.allocate(2+decoded.first.length).order(ByteOrder.LITTLE_ENDIAN).putShort(decoded.second.toShort()).put(decoded.first.toByteArray(Charsets.US_ASCII)).array()
                    }
                }
            } finally { work.options.compareAndSet(run,null) }
        }
    }

    private fun checkWork(work: Work) { check(!work.cancelled.get()) { "VISION_CANCELLED_OR_TIMEOUT" } }
    private fun failure(code: String)=byteArrayOf(1)+code.toByteArray(Charsets.US_ASCII)
    override fun close() { active.get()?.cancel(); deadlines.shutdownNow(); session?.close(); session=null }
}

internal fun checkedVisionRect(width: Int, height: Int, options: IntArray): Rect {
    require(options.size==5)
    val (left,top,right,bottom)=options
    require(left>=0 && top>=0 && right>left && bottom>top && right<=width && bottom<=height)
    require((right-left).toLong()*(bottom-top)<=4_194_304)
    return Rect(left,top,right-left,bottom-top)
}

internal fun decodeAlphanumeric(steps: Array<FloatArray>, dictionary: List<String>, threshold: Int): Pair<String,Int> {
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
