package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorCaptureVisibilityTest {
    @Test fun dimLayerNeedsASettlingFloorEvenWithAnimationsDisabled() {
        assertEquals(500L, editorCaptureSettleMillis(0, 1f, 1f))
        assertEquals(500L, editorCaptureSettleMillis(0, 0f, 0f))
        assertEquals(500L, editorCaptureSettleMillis(-1, 0f, 0f))
    }

    @Test fun respectsUserDelayAndBoundedAnimationScalesWithoutChangingSettings() {
        assertEquals(800L, editorCaptureSettleMillis(0, 2f, 1f))
        assertEquals(3_200L, editorCaptureSettleMillis(0, 1f, 10f))
        assertEquals(5_000L, editorCaptureSettleMillis(5_000L, 1f, 1f))
        assertEquals(500L, editorCaptureSettleMillis(0, Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(500L, editorCaptureSettleMillis(0, -1f, 11f))
    }
}
