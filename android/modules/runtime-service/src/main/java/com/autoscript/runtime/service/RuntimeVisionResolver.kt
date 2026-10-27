package com.autoscript.runtime.service

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class VisionResolvedBackend(val identity: String, val backend: NativeVisionBackend, val spiVersion: Int = 1)
internal data class VisionRuntimeInspection(val identity: String?, val state: NativeVisionAvailabilityState, val bundled: Boolean = false)
internal class VisionResolutionFailure(val state: NativeVisionAvailabilityState) : IllegalStateException("VISION_COMPONENT_${state.name}")

/** One service-owned cache, bounded IO, no Binder hashing and no loading during a read. */
internal class RuntimeVisionResolver(
    private val bundled: Set<String>,
    private val load: (String, () -> Unit) -> VisionResolvedBackend,
    private val inspect: (String, () -> Unit) -> VisionRuntimeInspection,
    private val nanoTime: () -> Long = System::nanoTime,
    private val selectedIdentity: (String) -> String? = { null },
) : NativeVisionBackendSource {
    private val lock = Any()
    private var closed = false
    private val backends = mutableMapOf<String, VisionResolvedBackend>()
    private val failures = mutableMapOf<String, NativeVisionAvailabilityState>()
    private val initialized = mutableSetOf<String>()
    private val refreshing = AtomicBoolean(false)
    private var lastRefresh: Long? = null
    private val cache = AtomicReference(KINDS.associateWith { kind ->
        VisionRuntimeInspection(if (kind in bundled) "bundled" else null,
            if (kind in bundled) NativeVisionAvailabilityState.AVAILABLE else NativeVisionAvailabilityState.CHECKING, kind in bundled)
    })
    private val io = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "vision-provider-io").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }

    override fun resolve(operation: Int, work: NativeVisionWork): NativeVisionBackend = resolve(operation, work, LOAD_NANOS)

    private fun resolve(operation: Int, work: NativeVisionWork, loadNanos: Long): NativeVisionBackend {
        work.checkActive()
        val kind = when (operation) { 5108,5109 -> "opencv"; 6101,6104,6105 -> "onnx"; else -> throw IllegalArgumentException("VISION_OPERATION_INVALID") }
        fun compatible(loaded: VisionResolvedBackend): NativeVisionBackend {
            if (operation == 6105 && kind !in bundled && loaded.spiVersion < 3)
                throw VisionResolutionFailure(NativeVisionAvailabilityState.INCOMPATIBLE)
            if (operation in setOf(5109, 6104) && kind !in bundled && loaded.spiVersion < 2)
                throw VisionResolutionFailure(NativeVisionAvailabilityState.INCOMPATIBLE)
            return loaded.backend
        }
        synchronized(lock) {
            check(!closed) { "VISION_CLOSED" }
            val state = cache.get().getValue(kind).state
            if (backends.containsKey(kind) && state != NativeVisionAvailabilityState.AVAILABLE)
                throw VisionResolutionFailure(state)
            backends[kind]?.let { return compatible(it) }
        }
        val start = nanoTime()
        val checkpoint = {
            work.checkActive()
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            synchronized(lock) { check(!closed) { "VISION_CLOSED" } }
            check(nanoTime() - start < loadNanos) { "VISION_PROVIDER_LOAD_TIMEOUT" }
        }
        val task = FutureTask {
            var candidate: NativeVisionBackend? = null
            try {
                checkpoint()
                synchronized(lock) { backends[kind] }?.let { return@FutureTask it.backend }
                val loaded = load(kind, checkpoint)
                candidate = loaded.backend
                checkpoint()
                val session = VisionRuntimeSession(loaded.backend) {
                    synchronized(lock) {
                        if (!closed) {
                            failures[kind] = NativeVisionAvailabilityState.LOAD_FAILED
                            cache.set(cache.get() + (kind to VisionRuntimeInspection(loaded.identity, NativeVisionAvailabilityState.LOAD_FAILED, kind in bundled)))
                        }
                    }
                }
                synchronized(lock) {
                    checkpoint()
                    backends[kind] = loaded.copy(backend = session)
                    failures.remove(kind)
                    candidate = null
                    cache.set(cache.get() + (kind to VisionRuntimeInspection(loaded.identity, NativeVisionAvailabilityState.AVAILABLE, kind in bundled)))
                }
                session
            } catch (error: Throwable) {
                if (!work.isCancelled() && error !is CancellationException && error !is InterruptedException) synchronized(lock) {
                    if (!closed) {
                        val state = (error as? VisionResolutionFailure)?.state ?: NativeVisionAvailabilityState.LOAD_FAILED
                        failures[kind] = state
                        cache.set(cache.get() + (kind to VisionRuntimeInspection(null, state, kind in bundled)))
                    }
                }
                throw error
            } finally { candidate?.close() }
        }
        val cancel: () -> Unit = { task.cancel(true); io.remove(task) }
        work.installCancellation(cancel)
        try {
            work.checkActive()
            try { io.execute(task) } catch (_: RejectedExecutionException) { throw VisionResolutionFailure(NativeVisionAvailabilityState.BUSY) }
            task.get(loadNanos, TimeUnit.NANOSECONDS)
            work.checkActive()
            return synchronized(lock) { compatible(requireNotNull(backends[kind])) }
        } catch (error: ExecutionException) { throw requireNotNull(error.cause) }
        catch (_: TimeoutException) { work.cancel(); throw VisionResolutionFailure(NativeVisionAvailabilityState.BUSY) }
        catch (error: InterruptedException) { work.cancel(); Thread.currentThread().interrupt(); throw error }
        finally { work.clearCancellation(cancel) }
    }

    /** Called on the startup worker, never Binder; no device pixels or input. */
    fun prepare(kind: String, work: NativeVisionWork): String {
        require(kind in KINDS)
        val checkTask = FutureTask {
            val checkpoint = { work.checkActive(); if (Thread.currentThread().isInterrupted) throw CancellationException() }
            val checked = checkedInspection(kind, checkpoint)
            checkpoint()
            synchronized(lock) {
                checkpoint()
                check(!closed) { "VISION_CLOSED" }
                val state = publishInspection(kind, checked).state
                if (checked.state !in setOf(NativeVisionAvailabilityState.AVAILABLE, NativeVisionAvailabilityState.INSTALLED))
                    throw VisionResolutionFailure(checked.state)
                if (backends[kind] != null && state !in setOf(NativeVisionAvailabilityState.AVAILABLE, NativeVisionAvailabilityState.INSTALLED))
                    throw VisionResolutionFailure(state)
            }
        }
        val cancel: () -> Unit = { checkTask.cancel(true); io.remove(checkTask) }
        work.installCancellation(cancel)
        try {
            work.checkActive()
            try { io.execute(checkTask) } catch (_: RejectedExecutionException) { throw VisionResolutionFailure(NativeVisionAvailabilityState.BUSY) }
            checkTask.get(30, TimeUnit.SECONDS)
        } catch (error: ExecutionException) { throw requireNotNull(error.cause) }
        catch (_: TimeoutException) { work.cancel(); throw VisionResolutionFailure(NativeVisionAvailabilityState.BUSY) }
        catch (error: InterruptedException) { work.cancel(); Thread.currentThread().interrupt(); throw error }
        finally { work.clearCancellation(cancel) }
        work.checkActive()
        val backend = resolve(if (kind == "opencv") 5108 else 6101, work, 30_000_000_000L)
        synchronized(lock) { if (kind in initialized) return requireNotNull(backends[kind]).identity }
        val pixels = ByteBuffer.allocateDirect(4 * 8 * 8)
        repeat(8 * 8) { i -> val color = (i * 37 % 256).toByte(); pixels.put(color).put(color).put(color).put(255.toByte()) }
        pixels.flip()
        val metadata = intArrayOf(8, 8, 32, 1)
        try {
            backend.execute(work, pixels, metadata, if (kind == "opencv") pixels.asReadOnlyBuffer() else null,
                if (kind == "opencv") metadata.copyOf() else intArrayOf(), intArrayOf(0, 0, 8, 8, 1000))
        } catch (error: Throwable) {
            if (work.isCancelled() || error is CancellationException || error is InterruptedException) throw error
            if (error.message == "VISION_BUSY_OR_CLOSED") throw VisionResolutionFailure(NativeVisionAvailabilityState.BUSY)
            synchronized(lock) {
                if (!closed) {
                    failures[kind] = NativeVisionAvailabilityState.LOAD_FAILED
                    cache.set(cache.get() + (kind to cache.get().getValue(kind).copy(state = NativeVisionAvailabilityState.LOAD_FAILED)))
                }
            }
            throw VisionResolutionFailure(NativeVisionAvailabilityState.LOAD_FAILED)
        }
        work.checkActive()
        return synchronized(lock) {
            work.checkActive()
            check(!closed) { "VISION_CLOSED" }
            initialized.add(kind); requireNotNull(backends[kind]).identity
        }
    }

    /** Fixed tiny selection records only; no directory scan or APK/model hashing. */
    fun selectionsMatch(identities: Map<String, String>): Boolean = identities.all { (kind, identity) ->
        if (kind in bundled) identity == "bundled" else selectedIdentity(kind) == identity
    }

    /** Returns only cached data; requests a single-flight, throttled explicit-query refresh. */
    fun availability(): Array<String> {
        refresh()
        return synchronized(lock) { val current = cache.get(); KINDS.map { kind ->
            val item = current.getValue(kind)
            "$kind=${item.state.name},source=${if (item.bundled) "bundled" else "provider"},loaded=${backends.containsKey(kind)}"
        }.toTypedArray() }
    }

    private fun refresh() {
        val start = nanoTime()
        synchronized(lock) {
            if (closed || lastRefresh?.let { start - it < REFRESH_NANOS } == true || !refreshing.compareAndSet(false, true)) return
            lastRefresh = start
        }
        try { io.execute {
            try {
                val checkpoint = {
                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                    synchronized(lock) { check(!closed) { "VISION_CLOSED" } }
                    check(nanoTime() - start < 30_000_000_000L) { "VISION_PROVIDER_INSPECT_TIMEOUT" }
                }
                for (kind in KINDS) {
                    checkpoint()
                    val checked = checkedInspection(kind, checkpoint)
                    checkpoint()
                    synchronized(lock) {
                        if (!closed) {
                            publishInspection(kind, checked)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Close, cancellation or a deadline must not become an uncaught
                // background exception that terminates the Android process.
                synchronized(lock) {
                    if (!closed) cache.set(cache.get().mapValues { (_, previous) ->
                        if (previous.state == NativeVisionAvailabilityState.CHECKING)
                            previous.copy(state = NativeVisionAvailabilityState.BUSY) else previous
                    })
                }
            } finally { refreshing.set(false) }
        } } catch (_: RejectedExecutionException) { refreshing.set(false) }
    }

    private fun checkedInspection(kind: String, checkpoint: () -> Unit): VisionRuntimeInspection = try {
        inspect(kind, checkpoint)
    } catch (error: Throwable) {
        if (error is CancellationException || error is InterruptedException) throw error
        VisionRuntimeInspection(null, (error as? VisionResolutionFailure)?.state ?: NativeVisionAvailabilityState.CORRUPT, kind in bundled)
    }

    /** Caller holds lock; preparation and status refresh must use the same readiness rules. */
    private fun publishInspection(kind: String, checked: VisionRuntimeInspection): VisionRuntimeInspection {
        val active = backends[kind]
        val state = when {
            active != null && checked.state in setOf(NativeVisionAvailabilityState.BUSY, NativeVisionAvailabilityState.CHECKING) -> cache.get().getValue(kind).state
            checked.state !in setOf(NativeVisionAvailabilityState.AVAILABLE, NativeVisionAvailabilityState.INSTALLED) -> checked.state
            active != null && active.identity != checked.identity -> NativeVisionAvailabilityState.RESTART_REQUIRED
            else -> failures[kind] ?: if (active != null) NativeVisionAvailabilityState.AVAILABLE else checked.state
        }
        return checked.copy(state = state).also { cache.set(cache.get() + (kind to it)) }
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            backends.values.map { it.backend }.also { backends.clear(); initialized.clear() }
        }
        io.shutdownNow().forEach { (it as? FutureTask<*>)?.cancel(true) }
        releaseVisionResources(*owned.map { backend -> { backend.close() } }.toTypedArray())
    }

    companion object {
        private val KINDS = listOf("opencv", "onnx")
        private const val LOAD_NANOS = 5_000_000_000L
        private const val REFRESH_NANOS = 5_000_000_000L
    }
}

/** Fail busy rather than queuing SDK work; release only after the active call has returned. */
internal class VisionRuntimeSession(private val delegate: NativeVisionBackend,
    private val onInitializationFailure: () -> Unit = {}) : NativeVisionBackend {
    private val lifecycle = NativeVisionLifecycle()
    private val active = AtomicReference<NativeVisionWork?>(null)
    override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
        templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
        check(lifecycle.enter()) { "VISION_BUSY_OR_CLOSED" }
        active.set(work)
        try { if (lifecycle.isClosed()) work.cancel(); work.checkActive(); return delegate.execute(work, pixels, metadata, templatePixels, templateMetadata, options) }
        catch (error: Throwable) {
            if (!work.isCancelled() && (error is LinkageError || error is NativeVisionInitializationFailure)) onInitializationFailure()
            throw error
        } finally { active.compareAndSet(work, null); lifecycle.finish(delegate::close) }
    }
    override fun close() = lifecycle.close({ active.get()?.cancel() }, delegate::close)
}
