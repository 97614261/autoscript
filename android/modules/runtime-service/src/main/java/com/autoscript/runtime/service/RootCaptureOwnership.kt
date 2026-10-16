package com.autoscript.runtime.service

/** Transfers only the result, and closes both bitmap and hardware resources on any failure. */
internal fun <T : Any> ownedRootCaptureReadback(
    acquire: () -> T?,
    transform: (T) -> T?,
    recycle: (T) -> Unit,
    closeHardware: () -> Unit,
): T? {
    var source: T? = null
    var result: T? = null
    var failure: Throwable? = null
    try {
        source = acquire()
        result = source?.let(transform)
    } catch (caught: Throwable) {
        failure = caught
    }
    fun cleanup(block: () -> Unit) {
        try { block() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
        }
    }
    if (source !== result) source?.let { cleanup { recycle(it) } }
    cleanup(closeHardware)
    if (failure != null) {
        result?.let { cleanup { recycle(it) } }
        throw failure!!
    }
    return result
}
