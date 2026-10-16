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
        try { cancel() } finally { if (!running) release() }
    }
}

