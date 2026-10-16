package com.autoscript.runtime.client

import com.autoscript.runtime.api.RuntimeDebugReply
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class RuntimeDebugReadGateTest {
    @Test fun slowReadCannotAccumulateRequestsAndCompletionReleasesSlot() {
        val gate = RuntimeDebugReadGate()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<RuntimeDebugReply> { gate.read {
                entered.countDown(); check(finish.await(2, TimeUnit.SECONDS)); RuntimeDebugReply.unavailable()
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(20) { assertEquals(RuntimeDebugReply.BUSY, gate.read { error("overlapping read") }.status) }
            finish.countDown()
            pending.get(2, TimeUnit.SECONDS)
            assertEquals(RuntimeDebugReply.UNAVAILABLE, gate.read { RuntimeDebugReply.unavailable() }.status)
        } finally { finish.countDown(); worker.shutdownNow() }
    }
    @Test fun failureReleasesSlotWithoutRetainingPayload() {
        val gate = RuntimeDebugReadGate()
        assertThrows(IllegalStateException::class.java) { gate.read { error("unlogged private payload") } }
        assertEquals(RuntimeDebugReply.UNAVAILABLE, gate.read { RuntimeDebugReply.unavailable() }.status)
    }
}
