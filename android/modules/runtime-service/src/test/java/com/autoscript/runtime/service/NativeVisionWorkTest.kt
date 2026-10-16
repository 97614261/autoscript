package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeVisionWorkTest {
    @Test fun clearingWaitsForInFlightSdkCancellationBeforeOptionsCanClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearing = CountDownLatch(1)
        val work = NativeVisionWork(7)
        val cleared = AtomicBoolean(false)
        val hook: () -> Unit = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        work.installCancellation(hook)
        val cancel = Thread { work.cancel() }.apply { start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val clear = Thread { clearing.countDown(); work.clearCancellation(hook); cleared.set(true) }.apply { start() }
        try {
            assertTrue(clearing.await(1, TimeUnit.SECONDS))
            assertFalse(cleared.get())
        } finally {
            release.countDown()
            cancel.join(1000)
            clear.join(1000)
        }
        assertFalse(cancel.isAlive)
        assertFalse(clear.isAlive)
        assertTrue(cleared.get())
        work.cancel()
    }
    @Test fun cancellationBeforeHookInstallationStillTerminatesOnlyOnce() {
        val work = NativeVisionWork(7)
        var calls = 0
        val hook: () -> Unit = { calls++ }
        work.cancel()
        work.installCancellation(hook)
        work.cancel()
        assertTrue(work.isCancelled())
        assertEquals(1, calls)
        assertThrows(IllegalStateException::class.java) { work.checkActive() }
    }

    @Test fun replacingOrClearingAnUnownedHookDoesNotDetachTheSdkWork() {
        val work = NativeVisionWork(7)
        var calls = 0
        val hook: () -> Unit = { calls++ }
        work.installCancellation(hook)
        assertThrows(IllegalStateException::class.java) { work.installCancellation { } }
        work.clearCancellation { }
        work.cancel()
        assertEquals(1, calls)
        work.clearCancellation(hook)
    }

    @Test fun hookFailureDoesNotEscapeStopAndClearedHookIsNotCalled() {
        val failed = NativeVisionWork(7)
        failed.installCancellation { throw IllegalStateException("SDK failure") }
        failed.cancel()
        assertTrue(failed.isCancelled())
        val closed = NativeVisionWork(8)
        var calls = 0
        val hook: () -> Unit = { calls++ }
        closed.installCancellation(hook)
        closed.clearCancellation(hook)
        closed.cancel()
        assertEquals(0, calls)
    }

    @Test fun resourceFailuresStillReleaseAllOtherOwnersAndUseStableCode() {
        var released = false
        assertThrows(NativeVisionCleanupFailure::class.java) {
            releaseNativeVisionResources({ throw IllegalStateException("private failure") }, { released = true })
        }
        assertTrue(released)
    }
}
