package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePromptGeometryTest {
    @Test fun transientPriorityDoesNotMoveOriginsOrHideNonOverlappingWindows() {
        val window = runtimePromptGeometry(720, 1280, 260, 40, 0, 1000)
        assertTrue(runtimePromptsOverlap(window, window))
        assertTrue(runtimePromptsOverlap(window, window.copy(x = 259, y = 1039)))
        assertFalse(runtimePromptsOverlap(window, window.copy(y = 1040)))
        assertFalse(runtimePromptsOverlap(window, window.copy(x = 260)))
        assertEquals(RuntimePromptGeometry(260, 40, 0, 1000), window)
    }
    @Test fun originAndExplicitPixelCoordinatesAreNotRecentered() {
        assertEquals(RuntimePromptGeometry(100, 30, 0, 0), runtimePromptGeometry(720, 1280, 100, 30, 0, 0))
        assertEquals(RuntimePromptGeometry(100, 30, 130, 280), runtimePromptGeometry(720, 1280, 100, 30, 130, 280))
    }
    @Test fun axesCenterIndependentlyAndFollowScreenRotation() {
        assertEquals(RuntimePromptGeometry(100, 30, 310, 625), runtimePromptGeometry(720, 1280, 100, 30, -1, -1))
        assertEquals(590, runtimePromptGeometry(1280, 720, 100, 30, -1, 100).x)
        assertEquals(100, runtimePromptGeometry(1280, 720, 100, 30, -1, 100).y)
        assertEquals(20, runtimePromptGeometry(720, 1280, 100, 30, 20, -1).x)
    }
    @Test fun outOfBoundsAndOversizedWindowsStayOnScreen() {
        assertEquals(RuntimePromptGeometry(720, 1200, 0, 80), runtimePromptGeometry(720, 1280, 2160, 1200, 9999, 9999))
        assertEquals(RuntimePromptGeometry(1, 1, 0, 0), runtimePromptGeometry(0, 0, 100, 30, -1, -1))
    }
}
