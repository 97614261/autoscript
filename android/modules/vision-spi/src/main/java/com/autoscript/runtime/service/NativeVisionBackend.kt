package com.autoscript.runtime.service

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** SDK-neutral fixed operation surface, never exposed to user Lua or editor code. */
interface NativeVisionBackend : AutoCloseable {
    fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray
}

class NativeVisionWork(val id: Long) {
    private class Cancellation(val action: () -> Unit) {
        private val fired = AtomicBoolean(false)
        private var detached = false
        @Synchronized fun invoke() { if (!detached && fired.compareAndSet(false, true)) runCatching(action) }
        // Wait for a running SDK setter before its owner closes RunOptions; late calls become no-ops.
        @Synchronized fun detach() { detached = true }
    }
    private val cancelled = AtomicBoolean(false)
    private val cancellation = AtomicReference<Cancellation?>(null)

    fun cancel() { cancelled.set(true); cancellation.get()?.invoke() }
    fun isCancelled(): Boolean = cancelled.get()
    fun checkActive() { check(!cancelled.get()) { "VISION_CANCELLED_OR_TIMEOUT" } }
    fun installCancellation(action: () -> Unit) {
        val hook = Cancellation(action)
        check(cancellation.compareAndSet(null, hook)) { "VISION_CANCEL_HOOK_BUSY" }
        // Closes the race where stop arrived before the SDK RunOptions was installed.
        if (cancelled.get()) hook.invoke()
    }
    fun clearCancellation(action: () -> Unit) {
        val hook = cancellation.get() ?: return
        if (hook.action === action && cancellation.compareAndSet(hook, null)) hook.detach()
    }
}

class NativeVisionCleanupFailure : IllegalStateException("VISION_CLEANUP_FAILED")
class NativeVisionComponentMissing : IllegalStateException("VISION_COMPONENT_NOT_INSTALLED")
class NativeVisionInitializationFailure : IllegalStateException("VISION_INITIALIZATION_FAILED")

fun releaseNativeVisionResources(vararg releases: () -> Unit) {
    try { releaseVisionResources(*releases) }
    catch (_: Throwable) { throw NativeVisionCleanupFailure() }
}
