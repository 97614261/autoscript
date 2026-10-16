package com.autoscript.brand.ui

import org.junit.Assert.*
import org.junit.Test

class BrandMotionTest {
    @Test fun stoppedPausedAndFailureAreNotLoopingSuccess() {
        assertFalse(BrandMotion.repeats(BrandState.SUCCESS))
        assertEquals(1_400L, BrandMotion.durationMs(BrandState.SUCCESS))
        for (state in listOf(BrandState.PAUSED, BrandState.FAILED)) {
            assertFalse(BrandMotion.repeats(state)); assertEquals(0L, BrandMotion.durationMs(state))
        }
    }
    @Test fun idleIgnitionAndRunningUseDifferentLoopDurations() {
        for (state in listOf(BrandState.IDLE, BrandState.EXECUTING, BrandState.RUNNING)) {
            assertTrue(BrandMotion.repeats(state)); assertTrue(BrandMotion.durationMs(state) in 1_000L..3_000L)
        }
        assertEquals(1_200L, BrandMotion.durationMs(BrandState.EXECUTING))
    }
    @Test fun stateDurationsKeepStartPauseAndRunningVisuallyDistinct() {
        assertTrue(BrandMotion.durationMs(BrandState.EXECUTING) < BrandMotion.durationMs(BrandState.RUNNING))
        assertEquals(0L, BrandMotion.durationMs(BrandState.PAUSED))
        assertEquals(0L, BrandMotion.durationMs(BrandState.FAILED))
        assertEquals(0f, BrandMotion.ignition(-1f), 0f)
        assertEquals(1f, BrandMotion.ignition(2f), 0f)
    }
    @Test fun breathingAndRingStayBounded() {
        assertEquals(0f, BrandMotion.pulse(0f), .0001f)
        assertEquals(1f, BrandMotion.pulse(.5f), .0001f)
        assertEquals(0f, BrandMotion.pulse(1f), .0001f)
        assertEquals(0f, BrandMotion.ringProgress(-5f), 0f)
        assertEquals(1f, BrandMotion.ringProgress(5f), 0f)
        for (i in 0..100) assertTrue(BrandMotion.pulse(i / 100f) in 0f..1f)
    }
    @Test fun twoOrbitsLoopContinuouslyWithoutReversingTheirDirection() {
        assertEquals(360f, BrandMotion.orbitDegrees(1f) - BrandMotion.orbitDegrees(0f), .001f)
        assertEquals(-360f, BrandMotion.counterOrbitDegrees(1f) - BrandMotion.counterOrbitDegrees(0f), .001f)
        for (i in 0 until 100) {
            val a = i / 100f; val b = (i + 1) / 100f
            assertTrue(BrandMotion.orbitDegrees(b) > BrandMotion.orbitDegrees(a))
            assertTrue(BrandMotion.counterOrbitDegrees(b) < BrandMotion.counterOrbitDegrees(a))
            assertTrue(BrandMotion.tailSweep(a) in 46f..70f)
        }
        val headStep = BrandMotion.orbitDegrees(.001f) - BrandMotion.orbitDegrees(0f)
        val tailStep = BrandMotion.orbitDegrees(1f) - BrandMotion.orbitDegrees(.999f)
        assertEquals(headStep, tailStep, .001f)
    }
}
