package com.autoscript.runtime.service

import java.io.InputStream

/** Host-only fixed provider contract; never exposed to Lua or the visual editor. */
interface NativeVisionProvider {
    val spiVersion: Int
    val kind: String
    fun create(model: NativeVisionModelSource?): NativeVisionBackend
}

fun interface NativeVisionModelSource { fun open(): InputStream }

/** V2 fixed second model; no caller-selected names or paths. */
interface NativeVisionModelSet : NativeVisionModelSource { fun openV5(): InputStream }

object NativeVisionSpi {
    const val VERSION = 3
    const val V5_BYTES = 7_872_351
    const val V5_SHA256 = "c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8"
    const val OPENCV_FACTORY = "com.autoscript.runtime.service.components.OptionalOpenCvProvider"
    const val ONNX_FACTORY = "com.autoscript.runtime.service.components.OptionalOnnxProvider"
    const val MODEL_BYTES = 7_653_044
    const val MODEL_SHA256 = "e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318"
}
