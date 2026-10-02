package com.autoscript.runtime.service

import com.autoscript.runtime.api.InputPointAction
import com.autoscript.runtime.api.InputPointPickReply
import org.junit.Assert.*
import org.junit.Test

class InputPointPickContractTest {
    private val valid = InputPointPickReply(10, 0, "drag", 720, 1280, 120, 300, 500, 900, 800)

    @Test fun validatesIdentityScreenBoundsDurationAndBackend() {
        assertTrue(valid.validFor(10, 3))
        listOf(valid.copy(requestId = 11), valid.copy(action = "unknown"), valid.copy(width = 0),
            valid.copy(width = 16385), valid.copy(x1 = -1), valid.copy(y1 = 1280), valid.copy(x2 = 720),
            valid.copy(y2 = -1), valid.copy(durationMs = 99), valid.copy(durationMs = 5001), valid.copy(message = "x".repeat(161)),
            valid.copy(status = InputPointPickReply.CANCELLED), valid.copy(status = InputPointPickReply.FAILED))
            .forEach { assertFalse(it.validFor(10, 3)) }
        assertFalse(valid.validFor(10, 0))
        assertFalse(valid.validFor(10, 1))
        assertFalse(valid.validFor(10, 2))
    }

    @Test fun basicBackendOffersOnlyTapSwipeAndNoFakePointerActions() {
        assertEquals(listOf(InputPointAction.TAP, InputPointAction.SWIPE), InputPointAction.entries.filter { it.available(1) })
        assertTrue(InputPointAction.entries.all { it.available(3) })
        assertTrue(InputPointAction.entries.none { it.available(0) })
        assertTrue(InputPointAction.entries.none { it.available(2) })
    }

    @Test fun upRequiresNoCoordinatesButStillRequiresRealScreenAndPointerFeature() {
        val up = valid.copy(action = "up", x1 = -1, y1 = -1, x2 = -1, y2 = -1)
        assertTrue(up.validFor(10, 3))
        assertFalse(up.validFor(10, 1))
        assertFalse(up.copy(height = 0).validFor(10, 3))
    }
}
