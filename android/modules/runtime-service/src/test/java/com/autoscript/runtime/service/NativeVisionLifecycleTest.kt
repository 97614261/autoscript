package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class NativeVisionLifecycleTest {
    @Test fun releaseFailureStillClearsEveryResourceAndFinishesLease() {
        val lifecycle = NativeVisionLifecycle()
        assertTrue(lifecycle.enter())
        val order = mutableListOf<Int>()
        val first = IllegalStateException("first")
        val thrown = assertThrows(IllegalStateException::class.java) {
            releaseVisionResources(
                { order.add(1); throw first },
                { order.add(2); error("second") },
                { order.add(3) },
                { lifecycle.finish {} },
            )
        }
        assertSame(first, thrown)
        assertEquals(listOf(1, 2, 3), order)
        assertTrue(lifecycle.enter())
        lifecycle.finish {}
    }

    @Test fun failedCancellationStillReleasesIdleModelAndCannotReopen() {
        val lifecycle = NativeVisionLifecycle()
        var releases = 0
        assertThrows(IllegalStateException::class.java) {
            lifecycle.close({ error("cancel failed") }, { releases++ })
        }
        assertEquals(1, releases)
        assertTrue(lifecycle.isClosed())
        assertFalse(lifecycle.enter())
        lifecycle.close({}, { releases++ })
        assertEquals(1, releases)
    }

    @Test fun failedCancellationNeverReleasesModelStillInUse() {
        val lifecycle = NativeVisionLifecycle()
        var releases = 0
        assertTrue(lifecycle.enter())
        assertThrows(IllegalStateException::class.java) {
            lifecycle.close({ error("cancel failed") }, { releases++ })
        }
        assertEquals(0, releases)
        lifecycle.finish { releases++ }
        assertEquals(1, releases)
        assertFalse(lifecycle.enter())
    }
    @Test fun closingActiveWorkCancelsButDefersModelReleaseUntilCompletion() {
        val lifecycle = NativeVisionLifecycle()
        var cancels = 0; var releases = 0
        assertTrue(lifecycle.enter()); assertFalse(lifecycle.enter())
        lifecycle.close({ cancels++ }, { releases++ })
        assertEquals(1, cancels); assertEquals(0, releases)
        assertFalse(lifecycle.enter())
        lifecycle.finish { releases++ }
        assertEquals(1, releases)
        lifecycle.close({ cancels++ }, { releases++ })
        assertEquals(1, cancels); assertEquals(1, releases)
    }

    @Test fun normalCompletionAllowsRetryAndIdleCloseReleasesOnce() {
        val lifecycle = NativeVisionLifecycle()
        var releases = 0
        assertTrue(lifecycle.enter()); lifecycle.finish { releases++ }
        assertEquals(0, releases)
        assertTrue(lifecycle.enter()); lifecycle.finish { releases++ }
        lifecycle.close({}, { releases++ }); lifecycle.close({}, { releases++ })
        assertEquals(1, releases); assertTrue(lifecycle.isClosed()); assertFalse(lifecycle.enter())
    }

    @Test fun invalidBufferLayoutsAreRejectedWithoutLoadingNativeLibraries() {
        val valid = intArrayOf(10, 2, 48, 1)
        validateVisionBuffer(96, 96, true, valid)
        validateVisionBuffer(96, 96, true, valid.copyOf().apply { this[3] = 2 })
        listOf(intArrayOf(), intArrayOf(10, 2, 39, 1), intArrayOf(10, 2, -1, 1),
            intArrayOf(16384, 16384, Int.MAX_VALUE, 1), intArrayOf(10, 2, 48, 3)).forEach {
            assertThrows(IllegalArgumentException::class.java) { validateVisionBuffer(96, 96, true, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateVisionBuffer(96, 80, true, valid) }
        assertThrows(IllegalArgumentException::class.java) { validateVisionBuffer(96, 96, false, valid) }
        assertThrows(IllegalArgumentException::class.java) { validateVisionBuffer(95, 95, true, valid) }
    }
}
