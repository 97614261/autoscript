package com.autoscript.runtime.service.components

import com.autoscript.runtime.service.NativeGrayMatcher
import com.autoscript.runtime.service.NativeVisionBackend
import com.autoscript.runtime.service.NativeVisionModelSource
import com.autoscript.runtime.service.NativeVisionProvider
import com.autoscript.runtime.service.NativeVisionSpi

/** Fixed publisher component entry, not a script-selectable factory. */
class OptionalOpenCvProvider : NativeVisionProvider {
    override val spiVersion = NativeVisionSpi.VERSION
    override val kind = "opencv"
    override fun create(model: NativeVisionModelSource?): NativeVisionBackend {
        require(model == null)
        return NativeGrayMatcher()
    }
}
