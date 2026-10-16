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
    private val abort: (Long) -> Unit = {},
    private val toolsBeforeRequest: () -> Unit = {},
    private val toolsSubmit: ((Long, String, String, () -> Boolean, (Result<String>) -> Unit) -> Unit)? = null,
    private val toolsAbort: (Long) -> Unit = {},
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
        val dialog = operation in setOf("dialogConfirm", "dialogShow")
        val tools = id == "runtime-tools"
        if (work <= 0 || id.length !in 1..64 || operation.length > 32 || value.length > if (dialog || tools) 32768 else 8192) return failure("UI_REQUEST_INVALID")
        val waitMillis = if (tools && operation == "record") runCatching {
            org.json.JSONObject(value).getLong("durationMs").also { require(it in 1000..60000) } + 10000
        }.getOrElse { return failure("UI_REQUEST_INVALID") }
        else if (dialog) runCatching { com.autoscript.script.ui.ScriptDialogRequest.parse(id, operation, value).timeoutMs + 2000 }.getOrElse { return failure("UI_REQUEST_INVALID") } else timeoutMillis
        val pending = Pending(work)
        val token = currentToken()
        if (!active.compareAndSet(null, pending)) return failure("UI_QUEUE_FULL")
        try {
            if (work <= cancelledThrough.get()) return failure("UI_CANCELLED")
            if (!post {
                if (pending.cancelled.get()) return@post
                runCatching {
                    if (tools) toolsBeforeRequest() else beforeRequest(token)
                    val complete: (Result<String>) -> Unit = { result ->
                        result.fold(
                            onSuccess = { text ->
                                val bytes = text.toByteArray(Charsets.UTF_8)
                                pending.result.set(if (bytes.size <= 32768 && '\u0000' !in text) byteArrayOf(0) + bytes else failure("UI_RESPONSE_INVALID"))
                            },
                            onFailure = { pending.result.set(failure("UI_COMMAND_INVALID")) },
                        )
                        pending.done.countDown()
                    }
                    if (tools) requireNotNull(toolsSubmit)(work, operation, value, { pending.cancelled.get() }, complete)
                    else submit(token, id, operation, value, { pending.cancelled.get() }, complete)
                }.onFailure { pending.result.set(failure("UI_SESSION_STALE")); pending.done.countDown() }
            }) return failure("UI_UNAVAILABLE")
            if (!pending.done.await(waitMillis, TimeUnit.MILLISECONDS)) {
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
            if (pending.cancelled.get()) runCatching { if (tools) toolsAbort(work) else abort(token) }
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
