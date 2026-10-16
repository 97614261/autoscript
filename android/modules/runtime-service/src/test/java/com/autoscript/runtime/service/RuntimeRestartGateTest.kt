package com.autoscript.runtime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRestartGateTest {
    @Test fun onlyTheSameReadyRunCanRestartWithinItsBudget() {
        fun accepts(stop: Boolean = false, generation: Long = 3, handle: Long = 7,
            ready: Boolean = true, count: Int = 0, busy: Boolean = false) =
            canRestartRuntime(stop, 3, generation, 7, handle, ready, count, busy)
        assertTrue(accepts())
        assertTrue(accepts(count = 9))
        assertFalse(accepts(stop = true))
        assertFalse(accepts(generation = 4))
        assertFalse(accepts(handle = 8))
        assertFalse(accepts(handle = 0))
        assertFalse(accepts(ready = false))
        assertFalse(accepts(count = 10))
        assertFalse(accepts(count = -1))
        assertFalse(accepts(busy = true))
    }
}
