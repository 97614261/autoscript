package com.autoscript.runtime.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class NativeScriptUiAdapterTest {
    @Test fun runtimeToolsDoNotBorrowUiControlEpochAndCancellationAbortsOnlyTheirWork() {
        var tool = false
        var aborted = 0L
        val adapter = NativeScriptUiAdapter(post = { it(); true }, currentToken = { 12 },
            beforeRequest = { fail("tools must not use UI epoch") }, submit = { _, _, _, _, _, _ -> fail("not a UI command") },
            toolsBeforeRequest = { tool = true }, toolsSubmit = { work, operation, value, _, complete ->
                assertEquals(7L, work); assertEquals("audioGetVolume", operation); assertEquals("", value)
                complete(Result.success("25"))
            }, toolsAbort = { aborted = it })
        assertArrayEquals(byteArrayOf(0) + "25".toByteArray(), adapter.dispatch(7, "runtime-tools", "audioGetVolume", ""))
        assertTrue(tool); assertEquals(0L, aborted)
    }
    @Test fun waitsForRealHostCompletionAndPreservesExactPropertyText() {
        val order = mutableListOf<String>()
        val adapter = NativeScriptUiAdapter(
            post = { it(); true }, currentToken = { 7 },
            beforeRequest = { order += "flush:$it" },
            submit = { token, id, operation, value, cancelled, complete ->
                assertEquals(7L, token); assertEquals("label", id); assertEquals("get:textColor", operation)
                assertEquals("", value); assertFalse(cancelled())
                order += "read"
                complete(Result.success("#8020aB43"))
            },
        )
        assertArrayEquals(byteArrayOf(0) + "#8020aB43".toByteArray(), adapter.dispatch(1, "label", "get:textColor", ""))
        assertEquals(listOf("flush:7", "read"), order)
    }

    @Test fun cancellationUnblocksWaitAndQueuedWorkCannotMutateControls() {
        val queued = AtomicReference<(() -> Unit)?>()
        val ready = CountDownLatch(1)
        val result = AtomicReference<ByteArray>()
        var changed = false
        val adapter = NativeScriptUiAdapter(post = { queued.set(it); ready.countDown(); true }, currentToken = { 1 },
            beforeRequest = {}, submit = { _, _, _, _, _, complete -> changed = true; complete(Result.success("")) })
        val worker = Thread { result.set(adapter.dispatch(2, "label", "x", "99")) }.apply { start() }
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        adapter.cancel(2); worker.join(1_000)
        assertFalse(worker.isAlive)
        queued.get()?.invoke()
        assertFalse(changed)
        assertArrayEquals(failure("UI_CANCELLED"), result.get())
    }

    @Test fun cancellationBeforeDispatchIsRememberedWithoutAnUnboundedMap() {
        var posted = false
        val adapter = NativeScriptUiAdapter(post = { posted = true; true }, currentToken = { 1 }, beforeRequest = {},
            submit = { _, _, _, _, _, _ -> fail("Cancelled request should not submit") })
        adapter.cancel(4)
        assertArrayEquals(failure("UI_CANCELLED"), adapter.dispatch(4, "label", "x", "99"))
        assertFalse(posted)
    }

    @Test fun timeoutPreventsLateQueuedWriteAndReturnsStableError() {
        val queued = AtomicReference<(() -> Unit)?>()
        var changed = false
        val adapter = NativeScriptUiAdapter(post = { queued.set(it); true }, currentToken = { 1 }, beforeRequest = {},
            submit = { _, _, _, _, _, _ -> changed = true }, timeoutMillis = 10)
        assertArrayEquals(failure("UI_TIMEOUT"), adapter.dispatch(5, "label", "x", "99"))
        queued.get()?.invoke()
        assertFalse(changed)
    }

    @Test fun staleEpochAndHostFailuresNeverReturnPrivateDiagnosticText() {
        val adapter = NativeScriptUiAdapter(post = { it(); true }, currentToken = { 1 },
            beforeRequest = { error("private/script.lua") }, submit = { _, _, _, _, _, _ -> fail("stale") })
        assertArrayEquals(failure("UI_SESSION_STALE"), adapter.dispatch(6, "label", "get:x", ""))
        val invalid = NativeScriptUiAdapter(post = { it(); true }, currentToken = { 1 }, beforeRequest = {},
            submit = { _, _, _, _, _, complete -> complete(Result.failure(IllegalArgumentException("private/script.lua"))) })
        assertArrayEquals(failure("UI_COMMAND_INVALID"), invalid.dispatch(7, "missing", "get:x", ""))
    }

    private fun failure(code: String) = byteArrayOf(1) + code.toByteArray(Charsets.US_ASCII)

    @Test fun dialogWaitUsesItsBoundAndCancellationRequestsWindowCleanup() {
        val ready = CountDownLatch(1)
        val cleaned = AtomicReference<Long>()
        val result = AtomicReference<ByteArray>()
        val adapter = NativeScriptUiAdapter(post = { it(); true }, currentToken = { 9 }, beforeRequest = {},
            submit = { _, _, _, _, _, _ -> ready.countDown() }, timeoutMillis = 1, abort = { cleaned.set(it) })
        val worker = Thread { result.set(adapter.dispatch(8,"main","dialogShow","""{"blockOutside":true,"timeoutMs":60000}""")) }.apply { start() }
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        adapter.cancel(8); worker.join(1000)
        assertFalse(worker.isAlive)
        assertEquals(9L,cleaned.get())
        assertArrayEquals(failure("UI_CANCELLED"),result.get())
    }

    @Test fun dialogDispatchPreservesChoiceAndRejectsMalformedDataBeforePosting() {
        var called = false
        val adapter = NativeScriptUiAdapter(post = { it(); true }, currentToken = { 1 }, beforeRequest = {},
            submit = { _, _, _, _, _, complete -> called = true; complete(Result.success("dismiss")) })
        assertArrayEquals(failure("UI_REQUEST_INVALID"),adapter.dispatch(10,"main","dialogShow","{}"))
        assertFalse(called)
        assertArrayEquals(byteArrayOf(0)+"dismiss".toByteArray(),adapter.dispatch(11,"main","dialogShow","""{"blockOutside":false,"timeoutMs":1000}"""))
        assertTrue(called)
    }
}
