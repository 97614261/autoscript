package com.autoscript.runtime.service

import android.content.res.AssetManager

/** Resolver ownership is independent from an individual script/preview request gate. */
internal interface NativeVisionBackendSource : AutoCloseable {
    fun resolve(operation: Int, work: NativeVisionWork): NativeVisionBackend
}

internal class BundledNativeVisionSource(assets: AssetManager) : NativeVisionBackendSource {
    private val gray = createGrayBackend()
    private val onnx = createOnnxBackend(assets)
    override fun resolve(operation: Int, work: NativeVisionWork): NativeVisionBackend =
        (if (operation in setOf(5108,5109)) gray else onnx) ?: throw NativeVisionComponentMissing()
    override fun close() = releaseVisionResources({ gray?.close() }, { onnx?.close() })
}
