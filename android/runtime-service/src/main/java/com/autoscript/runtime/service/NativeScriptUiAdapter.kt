package com.autoscript.runtime.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Fixed JNI callback. Waits on the host worker, never on the Lua or Android UI thread. */
internal class NativeScriptUiAdapter(
    private val post: (() -> Unit) -> Boolean,
    private val currentToken: () -> Long,
    private val beforeRequest: (Long) -> Unit,
    private val submit: (Long, String, String, String, () -> Boolean, (Result<String>) -> Unit) -> Unit,
    private val timeoutMillis: Long = 2_000,
) {
    private class Pending(val work: Long) {
        val done = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val result = AtomicReference<ByteArray?>()
    }
    private val active = AtomicReference<Pending?>()
    private val cancelledThrough = AtomicLong()

    @Suppress("unused") // Fixed JNI method signature.
    fun dispatch(work: Long, id: String, operation: String, value: String): ByteArray {
        if (work <= 0 || id.length !in 1..64 || operation.length > 32 || value.length > 8192) return failure("UI_REQUEST_INVALID")
        val pending = Pending(work)
        if (!active.compareAndSet(null, pending)) return failure("UI_QUEUE_FULL")
        try {
            val token = currentToken()
            if (work <= cancelledThrough.get()) return failure("UI_CANCELLED")
            if (!post {
                if (pending.cancelled.get()) return@post
                runCatching {
                    beforeRequest(token)
                    submit(token, id, operation, value, { pending.cancelled.get() }) { result ->
                        result.fold(
                            onSuccess = { text ->
                                val bytes = text.toByteArray(Charsets.UTF_8)
                                pending.result.set(if (bytes.size <= 32768 && '\u0000' !in text) byteArrayOf(0) + bytes else failure("UI_RESPONSE_INVALID"))
                            },
                            onFailure = { pending.result.set(failure("UI_COMMAND_INVALID")) },
                        )
                        pending.done.countDown()
                    }
                }.onFailure { pending.result.set(failure("UI_SESSION_STALE")); pending.done.countDown() }
            }) return failure("UI_UNAVAILABLE")
            if (!pending.done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                pending.cancelled.set(true)
                return failure("UI_TIMEOUT")
            }
            if (pending.cancelled.get()) return failure("UI_CANCELLED")
            return pending.result.get() ?: failure("UI_RESPONSE_INVALID")
        } catch (_: InterruptedException) {
            pending.cancelled.set(true)
            Thread.currentThread().interrupt()
            return failure("UI_CANCELLED")
        } catch (_: RuntimeException) {
            pending.cancelled.set(true)
            return failure("UI_UNAVAILABLE")
        } finally {
            active.compareAndSet(pending, null)
        }
    }

    @Suppress("unused") // Fixed JNI method signature; cancellation can precede registration.
    fun cancel(work: Long) {
        cancelledThrough.updateAndGet { previous -> maxOf(previous, work) }
        active.get()?.takeIf { it.work == work }?.let { it.cancelled.set(true); it.done.countDown() }
    }

    companion object {
        private fun failure(code: String) = byteArrayOf(1) + code.toByteArray(Charsets.US_ASCII)
    }
}
