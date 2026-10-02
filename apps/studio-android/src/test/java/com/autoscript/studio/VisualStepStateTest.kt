package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeDebugReply
import org.junit.Assert.*
import org.junit.Test

class VisualStepStateTest {
    @Test fun stepGateRejectsDoubleClicksAndUnlocksOnNextPause() {
        val state = VisualStepState()
        assertFalse(state.begin())
        state.paused(VisualDebugPosition("main", "a"))
        assertTrue(state.begin())
        assertFalse(state.begin())
        state.running()
        assertTrue(state.pending)
        assertNull(state.position)
        state.paused(VisualDebugPosition("main", "b"))
        assertFalse(state.pending)
        assertEquals("b", state.position?.nodeId)
        assertTrue(state.begin())
        state.rejected()
        assertFalse(state.pending)
    }

    @Test fun snapshotLoadSessionMismatchAndStopCannotEnableStep() {
        val state = VisualStepState()
        state.fetching()
        assertFalse(state.begin())
        state.paused(debugPosition(RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)))
        assertFalse(state.begin())
        state.paused(VisualDebugPosition("child", "node"))
        assertTrue(state.begin())
        state.reset()
        assertFalse(state.begin())
        assertFalse(state.pending)
        assertNull(debugPosition(RuntimeDebugReply(RuntimeDebugReply.SUCCESS, "", "", emptyList())))
    }

    @Test fun continueClearsBusyWhenRunningAndPauseEventsRemainDistinct() {
        val state = VisualStepState()
        state.paused(VisualDebugPosition("main", "a"))
        assertTrue(state.begin(continueRun = true))
        state.running()
        assertFalse(state.pending)
        val pause = RuntimeConnectionState(engineState = RuntimeEngineState.PAUSED, stateRevision = 1)
        assertNotEquals(pause, pause.copy(stateRevision = 2))
    }
}
