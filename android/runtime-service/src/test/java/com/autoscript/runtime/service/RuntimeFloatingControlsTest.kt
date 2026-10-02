package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol
import org.junit.Assert.*
import org.junit.Test

class RuntimeFloatingControlsTest {
    @Test fun runningBallExpandsControlsWithoutCamera() {
        val collapsed = runtimeFloatingControls(RuntimeProtocol.STATE_RUNNING, false, false)
        assertFalse(collapsed.expanded)
        assertFalse(collapsed.showCamera)
        val expanded = runtimeFloatingControls(RuntimeProtocol.STATE_RUNNING, false, true)
        assertTrue(expanded.expanded)
        assertTrue(expanded.canPauseResume)
        assertTrue(expanded.canStop)
        assertEquals("暂停", expanded.pauseLabel)
        assertEquals("继续", runtimeFloatingControls(RuntimeProtocol.STATE_PAUSED, false, true).pauseLabel)
    }

    @Test fun onlyExplicitCaptureModeShowsCamera() {
        val capture = runtimeFloatingControls(RuntimeProtocol.STATE_IDLE, true, true)
        assertTrue(capture.showCamera)
        assertFalse(capture.expanded)
        assertFalse(capture.canPauseResume)
        assertFalse(capture.canStop)
        listOf(RuntimeProtocol.STATE_STOPPING, RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED).forEach {
            val state = runtimeFloatingControls(it, false, true)
            assertFalse(state.canPauseResume)
            assertFalse(state.canStop)
            assertFalse(state.showCamera)
        }
    }
}
