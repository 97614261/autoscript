package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol as P
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class VisionPreparedReceipt(val id: Long, val generation: Long, val sourceDigest: String,
    val identities: Map<String, String>, val readyAt: Long)

/** Cancellation invalidates readiness, but never pretends an active SDK call returned. */
internal class RuntimeVisionPreparation(
    private val prepare: (String, NativeVisionWork) -> String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val deadlineMs: Long = 30_000,
) : AutoCloseable {
    private class Lease(val id: Long, val generation: Long, val sourceDigest: String,
        val work: NativeVisionWork, val callback: (Int, String, String) -> Unit) {
        val delivered = AtomicBoolean(false)
        val invalidated = AtomicBoolean(false)
        @Volatile var requestedStatus = -1
        @Volatile var thread: Thread? = null
        fun deliver(status: Int, kind: String = "", state: String = "") {
            if (delivered.compareAndSet(false, true)) {
                val terminal = requestedStatus.takeIf { it >= 0 } ?: status
                runCatching { callback(terminal, if (terminal == P.VISION_PREPARE_FAILED) kind else "",
                    if (terminal == P.VISION_PREPARE_FAILED) state else "") }
            }
        }
    }
    private val lock = Any()
    private var active: Lease? = null
    private var receipt: VisionPreparedReceipt? = null
    private var receiptRequired = false
    private val cancelled = linkedSetOf<Pair<Long, Long>>()
    private var closed = false
    // A completed request may submit its successor from the callback before this worker returns.
    private val io = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "vision-startup").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val deadlines = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "vision-startup-deadline").apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    val busy: Boolean get() = synchronized(lock) { active != null }

    fun begin(id: Long, generation: Long, sourceDigest: String, components: List<String>,
        current: () -> Boolean, callback: (Int, String, String) -> Unit): Int {
        if (id <= 0 || generation == 0L || !Regex("[0-9a-f]{64}").matches(sourceDigest) ||
            components != listOf("opencv", "onnx").filter { it in components }) return P.VISION_PREPARE_INVALID
        val lease = synchronized(lock) {
            if (closed || !current()) return P.VISION_PREPARE_STALE
            if (id to generation in cancelled) return P.VISION_PREPARE_CANCELLED
            if (active != null) return P.VISION_PREPARE_BUSY
            receipt = null
            receiptRequired = true
            Lease(id, generation, sourceDigest, NativeVisionWork(id), callback).also { active = it }
        }
        try { io.execute {
            lease.thread = Thread.currentThread()
            var expiry: java.util.concurrent.ScheduledFuture<*>? = null
            var kind = ""
            try {
                expiry = deadlines.schedule({
                    synchronized(lock) {
                        if (active !== lease || lease.delivered.get()) return@schedule
                        receipt = null; lease.invalidated.set(true)
                        if (lease.requestedStatus < 0) lease.requestedStatus = P.VISION_PREPARE_TIMEOUT
                    }
                    lease.work.cancel(); lease.thread?.interrupt(); lease.deliver(P.VISION_PREPARE_TIMEOUT)
                }, deadlineMs, TimeUnit.MILLISECONDS)
                val identities = linkedMapOf<String, String>()
                for (component in components) {
                    kind = component; lease.work.checkActive()
                    check(current()) { "VISION_START_STALE" }
                    identities[component] = prepare(component, lease.work)
                }
                lease.work.checkActive()
                val accepted = synchronized(lock) {
                    if (closed || active !== lease || !current() || lease.invalidated.get() || lease.delivered.get() || lease.work.isCancelled()) false
                    else {
                        receipt = VisionPreparedReceipt(id, generation, sourceDigest, identities.toMap(), nanoTime())
                        active = null
                        true
                    }
                }
                lease.deliver(if (accepted) P.VISION_PREPARE_ACCEPTED else P.VISION_PREPARE_STALE)
            } catch (error: Throwable) {
                val status = if (lease.invalidated.get() || lease.work.isCancelled() || error is InterruptedException) P.VISION_PREPARE_CANCELLED
                    else if (!current()) P.VISION_PREPARE_STALE else P.VISION_PREPARE_FAILED
                val state = if (status != P.VISION_PREPARE_FAILED) "" else
                    (error as? VisionResolutionFailure)?.state?.name ?:
                        if (error.message == "VISION_BUSY_OR_CLOSED") "BUSY" else "LOAD_FAILED"
                lease.deliver(status, if (status == P.VISION_PREPARE_FAILED) kind else "", state)
            } finally {
                expiry?.cancel(false)
                synchronized(lock) { if (active === lease) active = null }
                lease.thread = null
                Thread.interrupted()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(lock) { if (active === lease) active = null }
            return P.VISION_PREPARE_BUSY
        }
        return P.VISION_PREPARE_ACCEPTED
    }

    fun cancel(id: Long? = null, generation: Long? = null) {
        val lease = synchronized(lock) {
            if (id != null && generation != null) {
                cancelled.add(id to generation)
                while (cancelled.size > 64) cancelled.remove(cancelled.first())
                if ((active?.id != id || active?.generation != generation) &&
                    (receipt?.id != id || receipt?.generation != generation)) return
            }
            receipt = null
            active?.invalidated?.set(true)
            active?.let { if (it.requestedStatus < 0) it.requestedStatus = P.VISION_PREPARE_CANCELLED }
            active
        }
        lease?.work?.cancel(); lease?.thread?.interrupt(); lease?.deliver(P.VISION_PREPARE_CANCELLED)
    }

    fun takeReceipt(generation: Long, digest: String, selectionsMatch: (Map<String, String>) -> Boolean): Boolean = synchronized(lock) {
        if (active != null || closed) return false
        val item = receipt ?: return !receiptRequired // Historical/raw Lua requests retain lazy rejection.
        receipt = null
        item.generation == generation && item.sourceDigest == digest && nanoTime() - item.readyAt in 0..30_000_000_000L &&
            runCatching { selectionsMatch(item.identities) }.getOrDefault(false)
    }

    fun reset(): Boolean = synchronized(lock) {
        if (active != null || closed) false else { receipt = null; receiptRequired = false; true }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        cancel()
        io.shutdown(); deadlines.shutdown()
    }
}
