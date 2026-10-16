package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol as P
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RuntimeVisionPreparationTest {
    private val digest = "a".repeat(64)
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))

    @Test fun completionCallbackCanSubmitNextPreparationWithoutSpuriousBusy() {
        RuntimeVisionPreparation({ _, _ -> "identity" }).use { gate ->
            val done = CountDownLatch(1)
            val accepted = AtomicInteger(-1)
            val completed = AtomicInteger(-1)
            gate.begin(90, 1, digest, emptyList(), { true }) { _, _, _ ->
                accepted.set(gate.begin(91, 1, digest, emptyList(), { true }) { status, _, _ ->
                    completed.set(status); done.countDown()
                })
            }
            await(done)
            assertEquals(P.VISION_PREPARE_ACCEPTED, accepted.get())
            assertEquals(P.VISION_PREPARE_ACCEPTED, completed.get())
            assertTrue(gate.takeReceipt(1, digest) { it.isEmpty() })
            assertFalse(gate.takeReceipt(1, digest) { true })
        }
    }

    @Test fun environmentChangeDuringPreparationRejectsLateSuccess() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        RuntimeVisionPreparation({ _, _ -> entered.countDown(); release.await(); "identity" }).use { gate ->
            val done = CountDownLatch(1); val status = AtomicInteger(-1)
            gate.begin(9, -2, digest, listOf("opencv"), current::get) { s, _, _ -> status.set(s); done.countDown() }
            try { await(entered); current.set(false) } finally { release.countDown() }
            await(done); assertEquals(P.VISION_PREPARE_STALE, status.get())
            assertFalse(gate.takeReceipt(-2, digest) { true })
        }
    }

    @Test fun readyReceiptCanBeCancelledAndExpiryNeverFallsBackToRawStart() {
        val now = java.util.concurrent.atomic.AtomicLong(1)
        RuntimeVisionPreparation({ _, _ -> "identity" }, nanoTime = now::get).use { gate ->
            val done = CountDownLatch(1)
            gate.begin(10, 1, digest, emptyList(), { true }) { _, _, _ -> done.countDown() }
            await(done); gate.cancel(10, 1)
            assertFalse(gate.takeReceipt(1, digest) { true })
            assertTrue(gate.reset())
            val second = CountDownLatch(1)
            gate.begin(11, 1, digest, emptyList(), { true }) { _, _, _ -> second.countDown() }
            await(second); now.set(31_000_000_001L)
            assertFalse(gate.takeReceipt(1, digest) { true })
            assertFalse(gate.takeReceipt(1, digest) { true })
        }
    }

    @Test fun emptyDemandDoesNoSdkWorkAndSuccessIsSourceBound() {
        val calls = AtomicInteger()
        RuntimeVisionPreparation({ _, _ -> calls.incrementAndGet(); "identity" }).use { gate ->
            val done = CountDownLatch(1); val status = AtomicInteger(-1)
            assertEquals(P.VISION_PREPARE_ACCEPTED, gate.begin(1, -5, digest, emptyList(), { true }) { s, _, _ -> status.set(s); done.countDown() })
            await(done); assertEquals(P.VISION_PREPARE_ACCEPTED, status.get()); assertFalse(gate.busy)
            assertEquals(0, calls.get()); assertFalse(gate.takeReceipt(-5, "b".repeat(64)) { true })
            assertFalse(gate.takeReceipt(-5, digest) { true })
            assertTrue(gate.reset()); assertTrue(gate.takeReceipt(-5, digest) { true })
        }
    }

    @Test fun cancelledNativeCallStaysBusyAndLateSuccessCannotAuthorize() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val left = CountDownLatch(1)
        RuntimeVisionPreparation({ _, _ ->
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { /* SDK ignores interrupt. */ } }
            left.countDown(); "identity"
        }).use { gate ->
            val done = CountDownLatch(1); val statuses = AtomicInteger()
            gate.begin(2, 1, digest, listOf("opencv"), { true }) { status, _, _ ->
                assertEquals(P.VISION_PREPARE_CANCELLED, status); statuses.incrementAndGet(); done.countDown()
            }
            try {
                await(entered); gate.cancel(2, 1); await(done)
                assertTrue(gate.busy); assertFalse(gate.takeReceipt(1, digest) { true }); assertFalse(gate.reset())
                assertEquals(P.VISION_PREPARE_BUSY, gate.begin(3, 1, digest, emptyList(), { true }) { _, _, _ -> })
            } finally { release.countDown() }
            await(left)
            gate.close()
            assertFalse(gate.takeReceipt(1, digest) { true }); assertEquals(1, statuses.get())
        }
    }

    @Test fun staleEnvironmentAndChangedSelectionsRejectReceipt() {
        val identity = AtomicReference("original")
        RuntimeVisionPreparation({ _, _ -> identity.get() }).use { gate ->
            val done = CountDownLatch(1)
            gate.begin(4, 1, digest, listOf("opencv"), { true }) { status, _, _ -> assertEquals(P.VISION_PREPARE_ACCEPTED, status); done.countDown() }
            await(done); identity.set("changed")
            assertFalse(gate.takeReceipt(1, digest) { it["opencv"] == identity.get() })
            assertTrue(gate.reset())
            assertEquals(P.VISION_PREPARE_STALE, gate.begin(5, 1, digest, emptyList(), { false }) { _, _, _ -> })
        }
    }

    @Test fun earlyCancellationAndInvalidRequirementsNeverExecute() {
        RuntimeVisionPreparation({ _, _ -> error("must-not-execute") }).use { gate ->
            gate.cancel(6, -3)
            assertEquals(P.VISION_PREPARE_CANCELLED, gate.begin(6, -3, digest, listOf("onnx"), { true }) { _, _, _ -> })
            for (components in listOf(listOf("external"), listOf("onnx", "opencv"), listOf("onnx", "onnx")))
                assertEquals(P.VISION_PREPARE_INVALID, gate.begin(7, -3, digest, components, { true }) { _, _, _ -> })
        }
    }

    @Test fun timeoutIsOneShotAndDoesNotPermitLateStart() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        RuntimeVisionPreparation({ _, _ -> entered.countDown();
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) {} }; "identity"
        }, deadlineMs = 100).use { gate ->
            val done = CountDownLatch(1); val status = AtomicInteger(-1)
            gate.begin(8, 1, digest, listOf("onnx"), { true }) { s, _, _ -> status.set(s); done.countDown() }
            try { await(entered); await(done); assertEquals(P.VISION_PREPARE_TIMEOUT, status.get())
                assertTrue(gate.busy); assertFalse(gate.takeReceipt(1, digest) { true }) }
            finally { release.countDown() }
        }
    }
}
