package com.autoscript.runtime.service

import android.content.res.AssetManager
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Optional ONNX source set with bounded SDK-independent BGR preprocessing. */
class NativeOnnxRecognizer(private val modelSource: NativeVisionModelSource) : NativeVisionBackend {
    constructor(assets: AssetManager) : this(NativeVisionModelSource { assets.open("vision/en_PP-OCRv4_rec_mobile.onnx") })
    private var session: OrtSession? = null

    override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
        val input = prepareOnnxImage(pixels, metadata, options, work::checkActive)
        return recognize(input, options[4], work)
    }

    private fun model(work: NativeVisionWork): OrtSession {
        session?.let { return it }
        return try { initializeModel(work) }
        catch (error: Throwable) {
            if (work.isCancelled() || error is java.util.concurrent.CancellationException || error is InterruptedException) throw error
            throw NativeVisionInitializationFailure()
        }
    }

    private fun initializeModel(work: NativeVisionWork): OrtSession {
        val bytes=modelSource.open().use { stream ->
            val data=ByteArray(NativeVisionSpi.MODEL_BYTES)
            var offset=0
            while(offset<data.size) { work.checkActive(); val count=stream.read(data,offset,data.size-offset); require(count>0); offset+=count }
            require(stream.read()==-1); data
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash==NativeVisionSpi.MODEL_SHA256)
        val environment=OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        OrtSession.SessionOptions().use { config ->
            config.setIntraOpNumThreads(1); config.setInterOpNumThreads(1)
            config.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            return environment.createSession(bytes,config).also { session=it }
        }
    }

    private fun recognize(input: OnnxImageInput, threshold: Int, work: NativeVisionWork): ByteArray {
        work.checkActive()
        val inference=model(work)
        work.checkActive()
        OrtSession.RunOptions().use { run ->
            val cancelRun: () -> Unit = { run.setTerminate(true) }
            work.installCancellation(cancelRun)
            try {
                work.checkActive()
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input.tensor),longArrayOf(1,3,48,input.paddedWidth.toLong())).use { tensor ->
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
            } finally { work.clearCancellation(cancelRun) }
        }
    }

    override fun close() { val owned = session; session = null; owned?.close() }
}
