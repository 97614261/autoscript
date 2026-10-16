package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.runtime.api.RuntimeDebugReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeVariableReadTest {
    private val current = RuntimeConnectionState(sessionGeneration = 7, stateRevision = 10)
    @Test fun exceptionAllowsRetryAndOldFailureCannotClearANewerRequest() = runBlocking {
        val state = RuntimeVariableInspectionState()
        var failures = 0
        readRuntimeVariables(state, 7, 10, { error("private runtime payload") }, { current }) { failures++ }
        assertFalse(state.loading)
        assertEquals(1, failures)
        val old = state.begin()!!
        state.invalidate()
        val fresh = state.begin()!!
        state.finish(old)
        assertTrue(state.loading)
        state.finish(fresh)
        assertFalse(state.loading)
    }
    @Test fun cancellationIsNotAFailureAndReleasesTheRead() = runBlocking {
        val state = RuntimeVariableInspectionState()
        var failures = 0
        try {
            readRuntimeVariables(state, 7, 10, { throw CancellationException() }, { current }) { failures++ }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertFalse(state.loading)
        assertEquals(0, failures)
    }
    @Test fun changedSessionDiscardsLateReplies() = runBlocking {
        val state = RuntimeVariableInspectionState()
        readRuntimeVariables(state, 7, 10, { RuntimeDebugReply.unavailable() }, { current.copy(sessionGeneration = 8) }) { fail("unexpected failure") }
        assertFalse(state.loading)
        assertEquals(RuntimeDebugReply.UNAVAILABLE, state.reply.status)
    }
}
