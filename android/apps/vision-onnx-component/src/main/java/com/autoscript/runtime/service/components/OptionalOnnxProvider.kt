package com.autoscript.runtime.service.components

import com.autoscript.runtime.service.NativeOnnxRecognizer
import com.autoscript.runtime.service.NativeVisionBackend
import com.autoscript.runtime.service.NativeVisionModelSource
import com.autoscript.runtime.service.NativeVisionModelSet
import com.autoscript.runtime.service.NativeVisionProvider
import com.autoscript.runtime.service.NativeVisionSpi

/** Only accepts the host-owned, hash-pinned model source. */
class OptionalOnnxProvider : NativeVisionProvider {
    override val spiVersion = NativeVisionSpi.VERSION
    override val kind = "onnx"
    override fun create(model: NativeVisionModelSource?): NativeVisionBackend =
        NativeOnnxRecognizer(model as? NativeVisionModelSet ?: error("VISION_PROVIDER_MODEL_SET_REQUIRED"))
}
