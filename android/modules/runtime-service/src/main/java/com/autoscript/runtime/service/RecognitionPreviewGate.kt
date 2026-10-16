package com.autoscript.runtime.service

import com.autoscript.runtime.api.RecognitionPreviewReply
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One outstanding request; cancellation tombstones cover cancel-before-dispatch races. */
internal class RecognitionPreviewGate(private val busy: AtomicBoolean, private val now: () -> Long = System::nanoTime) {
    internal class Work(val id: Long, val generation: Long, val deadline: Long) {
        val cancelled = AtomicBoolean(false)
    }
    private val active = AtomicReference<Work?>(null)
    private val cancelled = LinkedHashSet<Pair<Long, Long>>()
    private val closed = AtomicBoolean(false)
    fun begin(id: Long, generation: Long): Work? {
        if (closed.get() || !busy.compareAndSet(false, true)) return null
        val work = Work(id, generation, now() + 5_000_000_000L)
        active.set(work)
        synchronized(cancelled) { if (cancelled.remove(id to generation)) work.cancelled.set(true) }
        if (closed.get()) work.cancelled.set(true)
        return work
    }
    fun cancel(id: Long, generation: Long): Work? {
        synchronized(cancelled) {
            cancelled.add(id to generation)
            while (cancelled.size > 64) cancelled.remove(cancelled.first())
        }
        return active.get()?.takeIf { it.id == id && it.generation == generation }?.also { it.cancelled.set(true) }
    }
    fun status(work: Work, generation: Long): Int? = when {
        generation != work.generation -> RecognitionPreviewReply.SESSION_MISMATCH
        now() >= work.deadline -> RecognitionPreviewReply.TIMEOUT
        closed.get() || work.cancelled.get() -> RecognitionPreviewReply.CANCELLED
        else -> null
    }
    fun finish(work: Work) {
        if (active.compareAndSet(work, null)) busy.set(false)
        synchronized(cancelled) { cancelled.remove(work.id to work.generation) }
    }
    fun close(): Work? { closed.set(true); return active.get()?.also { it.cancelled.set(true) } }
}
