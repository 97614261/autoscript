package com.autoscript.runtime.service

import java.nio.ByteBuffer

/** Session ownership only. Closing never releases the process-pinned native files. */
internal class LoadedVisionProvider(private val delegate: NativeVisionBackend, val spiVersion: Int = 1) : NativeVisionBackend, NativePolygonSearchBackend {
    override fun supportsPolygonSearch() = (delegate as? NativePolygonSearchBackend)?.supportsPolygonSearch() == true
    private var closed = false

    @Synchronized override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
        check(!closed) { "VISION_CLOSED" }
        return delegate.execute(work, pixels, metadata, templatePixels, templateMetadata, options)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }
}
