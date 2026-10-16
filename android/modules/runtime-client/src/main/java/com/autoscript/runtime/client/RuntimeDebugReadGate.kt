package com.autoscript.runtime.client

import com.autoscript.runtime.api.RuntimeDebugReply
import java.util.concurrent.atomic.AtomicBoolean

/** The slot lasts through the synchronous Binder call, even if its UI coroutine is cancelled. */
internal class RuntimeDebugReadGate {
    private val pending = AtomicBoolean(false)
    fun read(block: () -> RuntimeDebugReply): RuntimeDebugReply {
        if (!pending.compareAndSet(false, true)) return RuntimeDebugReply.unavailable(RuntimeDebugReply.BUSY)
        return try { block() } finally { pending.set(false) }
    }
}
