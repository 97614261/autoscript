package com.autoscript.runtime.service

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Identity is separate from screenshot context and changes even within one service generation. */
internal data class RuntimeDebugRun(val generation: Long, val handle: Long, val projectId: String, val revision: Long)

internal class RuntimeDebugSession {
    private val revision = AtomicLong()
    private val current = AtomicReference<RuntimeDebugRun?>(null)

    fun clear() { current.set(null) }

    fun started(generation: Long, handle: Long, projectId: String) {
        current.set(RuntimeDebugRun(generation, handle, projectId, revision.incrementAndGet()))
    }

    fun ticket(generation: Long, handle: Long, projectId: String): RuntimeDebugRun? = current.get()?.takeIf {
        it.generation == generation && it.handle == handle && it.projectId == projectId && projectId.isNotEmpty()
    }

    fun accepts(ticket: RuntimeDebugRun, generation: Long, handle: Long): Boolean =
        current.get() == ticket && ticket.generation == generation && ticket.handle == handle
}
