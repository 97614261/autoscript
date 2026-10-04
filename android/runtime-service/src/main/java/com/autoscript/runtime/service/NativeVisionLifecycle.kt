package com.autoscript.runtime.service

/** Closing cancels the active lease, but never frees a model still used by that lease. */
internal class NativeVisionLifecycle {
    private var closed = false
    private var running = false

    @Synchronized fun enter(): Boolean {
        if (closed || running) return false
        running = true
        return true
    }

    @Synchronized fun isClosed(): Boolean = closed

    @Synchronized fun finish(release: () -> Unit) {
        check(running)
        running = false
        if (closed) release()
    }

    @Synchronized fun close(cancel: () -> Unit, release: () -> Unit) {
        if (closed) return
        closed = true
        cancel()
        if (!running) release()
    }
}

internal fun validateVisionBuffer(capacity: Int, remaining: Int, direct: Boolean, metadata: IntArray) {
    require(metadata.size == 4 && metadata[0] in 1..16384 && metadata[1] in 1..16384 && metadata[3] in 1..2)
    val stride = metadata[2].toLong()
    val required = stride * metadata[1]
    require(stride >= metadata[0].toLong() * 4 && required in 1..(64L * 1024 * 1024) &&
        direct && capacity.toLong() == required && remaining == capacity)
}
