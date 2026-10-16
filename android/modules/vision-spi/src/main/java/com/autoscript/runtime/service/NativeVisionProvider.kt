package com.autoscript.runtime.service

import java.io.InputStream

/** Host-only fixed provider contract; never exposed to Lua or the visual editor. */
interface NativeVisionProvider {
    val spiVersion: Int
    val kind: String
    fun create(model: NativeVisionModelSource?): NativeVisionBackend
}

fun interface NativeVisionModelSource { fun open(): InputStream }

object NativeVisionSpi {
    const val VERSION = 1
    const val OPENCV_FACTORY = "com.autoscript.runtime.service.components.OptionalOpenCvProvider"
    const val ONNX_FACTORY = "com.autoscript.runtime.service.components.OptionalOnnxProvider"
    const val MODEL_BYTES = 7_653_044
    const val MODEL_SHA256 = "e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318"
}
