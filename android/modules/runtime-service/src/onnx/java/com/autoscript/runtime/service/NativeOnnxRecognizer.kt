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
class NativeOnnxRecognizer private constructor(private val modelSource:NativeVisionModelSource,private val expectedBytes:Int,
    private val expectedHash:String,private val candidateSource:NativeVisionModelSource?) : NativeVisionBackend {
    constructor(modelSource:NativeVisionModelSource):this(modelSource,NativeVisionSpi.MODEL_BYTES,NativeVisionSpi.MODEL_SHA256,null)
    constructor(models:NativeVisionModelSet):this(models,NativeVisionSpi.MODEL_BYTES,NativeVisionSpi.MODEL_SHA256,
        NativeVisionModelSource { models.openV5() })
    constructor(assets: AssetManager) : this(NativeVisionModelSource {assets.open("vision/en_PP-OCRv4_rec_mobile.onnx")},
        NativeVisionSpi.MODEL_BYTES,NativeVisionSpi.MODEL_SHA256,NativeVisionModelSource {assets.open("vision/en_PP-OCRv5_rec_mobile.onnx")})
    private var candidate:NativeOnnxRecognizer?=null
    private var session: OrtSession? = null
    // The dictionary is immutable model metadata; parsing it for every OCR frame adds avoidable
    // allocations to the inference tail.
    private var decodeDictionary: List<String>? = null

    override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
        if(options.size==6 && options[5]==2) {
            val selected=candidate ?: NativeOnnxRecognizer(candidateSource ?: throw NativeVisionComponentMissing(),
                NativeVisionSpi.V5_BYTES,NativeVisionSpi.V5_SHA256,null).also {candidate=it}
            return selected.execute(work,pixels,metadata,templatePixels,templateMetadata,options.copyOf(5))
        }
        val includeSymbols = options.size == 6 && options[5] == 3
        require(!includeSymbols || expectedBytes == NativeVisionSpi.MODEL_BYTES)
        val selectedOptions = if (includeSymbols) options.copyOf(5) else options
        val input = prepareOnnxImage(pixels, metadata, selectedOptions, work::checkActive)
        return recognize(input, selectedOptions[4], work, includeSymbols)
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
            val data=ByteArray(expectedBytes)
            var offset=0
            while(offset<data.size) { work.checkActive(); val count=stream.read(data,offset,data.size-offset); require(count>0); offset+=count }
            require(stream.read()==-1); data
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash==expectedHash)
        val environment=OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        OrtSession.SessionOptions().use { config ->
            config.setIntraOpNumThreads(1); config.setInterOpNumThreads(1)
            config.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            val initialized=environment.createSession(bytes,config)
            try {
                val characterDictionary = initialized.metadata.customMetadata["character"]
                    ?.trimEnd('\n')
                    ?.split('\n')
                    ?: error("OCR_DICTIONARY_MISSING")
                decodeDictionary = listOf("") + characterDictionary + listOf(" ")
                require(decodeDictionary!!.size==if(expectedBytes==NativeVisionSpi.V5_BYTES) 438 else 97)
                session=initialized
                return initialized
            } catch(error:Throwable) {
                initialized.close();throw error
            }
        }
    }

    private fun recognize(input: OnnxImageInput, threshold: Int, work: NativeVisionWork, includeSymbols: Boolean): ByteArray {
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
                        val dictionary = decodeDictionary ?: error("OCR_DICTIONARY_MISSING")
                        // Candidate classes are checked separately; never relax the default V4 budget.
                        val decoded=if(includeSymbols) decodeLatinText(values[0],dictionary,threshold)
                            else if(expectedBytes==NativeVisionSpi.V5_BYTES) decodeEnglishV5(values[0],dictionary,threshold)
                            else decodeAlphanumeric(values[0],dictionary,threshold)
                        return ByteBuffer.allocate(2+decoded.first.length).order(ByteOrder.LITTLE_ENDIAN).putShort(decoded.second.toShort()).put(decoded.first.toByteArray(Charsets.US_ASCII)).array()
                    }
                }
            } finally { work.clearCancellation(cancelRun) }
        }
    }

    override fun close() {
        val owned = session
        session = null
        decodeDictionary = null
        val selected=candidate;candidate=null
        releaseNativeVisionResources({owned?.close()},{selected?.close()})
    }
}
