package com.autoscript.runtime.service

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed JNI boundary; SDK-specific types and resources belong to the individual backends. */
internal class NativeVisionAdapter(private val source: NativeVisionBackendSource, private val ownsSource: Boolean = true) : AutoCloseable {
    constructor(assets: AssetManager) : this(BundledNativeVisionSource(assets))
    private val active = AtomicReference<NativeVisionWork?>(null)
    private val lifecycle = NativeVisionLifecycle()
    private val cancelled = LinkedHashSet<Long>()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "vision-deadline").apply { isDaemon = true }
    }

    @Suppress("unused")
    fun cancel(id: Long) {
        synchronized(cancelled) {
            cancelled.add(id)
            while (cancelled.size > 64) cancelled.remove(cancelled.first())
        }
        active.get()?.takeIf { it.id == id }?.cancel()
    }

    @Suppress("unused")
    @JvmOverloads // Preserve the fixed seven-argument JNI descriptor alongside preview's deadline.
    fun dispatch(id: Long, operation: Int, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray, deadlineNanos: Long? = null): ByteArray {
        val work = NativeVisionWork(id)
        if (!lifecycle.enter()) return failure(if (lifecycle.isClosed()) "VISION_CLOSED" else "VISION_BUSY")
        active.set(work)
        var expiry: ScheduledFuture<*>? = null
        var cleanupFailed = false
        val response = try {
            val remaining = deadlineNanos?.let { it-System.nanoTime() }?.coerceAtMost(5_000_000_000L) ?: 5_000_000_000L
            if(remaining <= 0L) work.cancel()
            expiry = deadlines.schedule({ work.cancel() }, remaining.coerceAtLeast(1L), TimeUnit.NANOSECONDS)
            if (lifecycle.isClosed()) work.cancel()
            synchronized(cancelled) { if (cancelled.remove(id)) work.cancel() }
            work.checkActive()
            require(operation in setOf(5108,5109,6101,6104,6105) && options.size == 5)
            validateVisionBuffer(pixels.capacity(), pixels.remaining(), pixels.isDirect, metadata)
            checkedVisionRect(metadata[0], metadata[1], options)
            require(options[4] in 0..1000)
            if (operation in setOf(5108,5109)) {
                require(templatePixels != null)
                validateVisionBuffer(templatePixels.capacity(), templatePixels.remaining(), templatePixels.isDirect, templateMetadata)
            }
            val backend = source.resolve(operation, work)
            val selectedOptions=when(operation) {5109->options+intArrayOf(1);6104->options+intArrayOf(2);6105->options+intArrayOf(3);else->options}
            val payload = backend.execute(work, pixels, metadata, templatePixels, templateMetadata, selectedOptions)
            work.checkActive()
            byteArrayOf(0) + payload
        } catch (error: VisionResolutionFailure) {
            failure(if (work.isCancelled()) "VISION_CANCELLED_OR_TIMEOUT" else "VISION_COMPONENT_${error.state.name}")
        } catch (_: NativeVisionComponentMissing) { failure("VISION_COMPONENT_NOT_INSTALLED")
        } catch (_: NativeVisionInitializationFailure) { failure("VISION_INITIALIZATION_FAILED")
        } catch (_: NativeVisionCleanupFailure) { failure("VISION_CLEANUP_FAILED")
        } catch (_: UnsatisfiedLinkError) { failure("VISION_LIBRARY_UNAVAILABLE")
        } catch (_: Throwable) {
            failure(if (lifecycle.isClosed()) "VISION_CLOSED" else if (work.isCancelled()) "VISION_CANCELLED_OR_TIMEOUT" else "VISION_OPERATION_FAILED")
        } finally {
            try {
                releaseVisionResources(
                    { expiry?.cancel(false) }, { active.compareAndSet(work, null) },
                    { synchronized(cancelled) { cancelled.remove(id) } }, { lifecycle.finish(::releaseBackends) },
                )
            } catch (_: Throwable) { cleanupFailed = true }
        }
        return if (cleanupFailed) failure("VISION_CLEANUP_FAILED") else response
    }

    private fun failure(code: String) = byteArrayOf(1) + code.toByteArray(Charsets.US_ASCII)
    private fun releaseBackends() { if (ownsSource) source.close() }
    override fun close() {
        lifecycle.close(cancel = { active.get()?.cancel(); deadlines.shutdownNow() }, release = ::releaseBackends)
    }
}
