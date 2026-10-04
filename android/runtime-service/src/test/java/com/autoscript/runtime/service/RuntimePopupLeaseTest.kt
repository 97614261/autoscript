package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class RuntimePopupLeaseTest {
    @Test fun replacementOldExpiryAndDoubleCloseDoNotDismissCurrentTip() {
        val state = RuntimePopupLease()
        repeat(100) {
            val first = state.begin()
            val replacement = state.begin()
            assertFalse(state.end(first))
            assertTrue(state.end(replacement))
            assertFalse(state.end(replacement))
        }
    }

    @Test fun stopClearAndRetryInvalidatePreviousRun() {
        val state = RuntimePopupLease()
        val previous = state.begin()
        state.clear()
        assertFalse(state.end(previous))
        val retry = state.begin()
        assertFalse(state.end(previous))
        assertTrue(state.end(retry))
    }
}
