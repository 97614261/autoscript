package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeDebugReply
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class VisualStepStateTest {
    @Test fun requestFailureCancellationAndRetryReleasePending() = runBlocking {
        val state = VisualStepState()
        state.paused(VisualDebugPosition("main", "a"))
        var errors = 0
        assertTrue(state.begin())
        requestVisualStep(state, { errors++ }) { throw IllegalStateException("offline") }
        assertFalse(state.pending)
        assertEquals(1, errors)
        assertTrue(state.begin())
        try {
            requestVisualStep(state, { errors++ }) { throw CancellationException("closed") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(state.pending)
        assertEquals(1, errors)
        assertTrue(state.begin())
        requestVisualStep(state, { errors++ }) { true }
        assertTrue(state.pending)
    }

    @Test fun lateRejectionCannotUnlockNewStepOrShowStaleError() = runBlocking {
        val state = VisualStepState()
        state.paused(VisualDebugPosition("main", "a"))
        assertTrue(state.begin())
        requestVisualStep(state, { fail("stale error") }) {
            state.paused(VisualDebugPosition("main", "b"))
            assertTrue(state.begin())
            false
        }
        assertTrue(state.pending)
        val old = state.fetching()
        val current = state.fetching()
        state.fetchFinished(old)
        assertTrue(state.loading)
        state.fetchFinished(current)
        assertFalse(state.loading)
    }

    @Test fun lateSnapshotCannotReenableStepAfterStopNewReadOrRunning() {
        val reply = RuntimeDebugReply(com.autoscript.runtime.api.RuntimeDebugReply.SUCCESS,
            "main", "next", emptyList(), runtimeState = com.autoscript.runtime.api.RuntimeProtocol.STATE_PAUSED)
        val state = VisualStepState()
        repeat(100) {
            val old = state.fetching()
            state.reset()
            val next = state.fetching()
            assertFalse(state.fetched(old, reply))
            assertTrue(state.loading)
            assertTrue(state.fetched(next, reply))
            assertTrue(state.begin())
            state.reset()
            assertFalse(state.fetched(next, reply))
            assertFalse(state.begin())
            val runningRead = state.fetching()
            state.running()
            assertFalse(state.fetched(runningRead, reply))
            assertNull(state.position)
        }
    }

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
