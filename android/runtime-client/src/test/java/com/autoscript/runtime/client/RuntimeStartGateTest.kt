package com.autoscript.runtime.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import org.junit.Assert.*
import org.junit.Test

class RuntimeStartGateTest {
    @Test fun duplicateRequestsAreRejectedAndOldCompletionCannotReleaseRetry() {
        val gate = RuntimeStartGate()
        val first = Job(); val retry = Job()
        assertTrue(gate.begin(first))
        assertFalse(gate.begin(retry))
        gate.finish(first)
        assertTrue(gate.begin(retry))
        gate.finish(first)
        assertFalse(gate.begin(Job()))
        gate.finish(retry)
        assertTrue(gate.begin(first))
    }

    @Test fun stopCancelsRootWaitAndNeverSubmitsAfterLateReady() = runBlocking {
        val gate = RuntimeStartGate()
        val waiting = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = 7,
            engineState = RuntimeEngineState.IDLE, rootState = RuntimeRootState.STARTING)
        val states = MutableStateFlow(waiting)
        val entered = CompletableDeferred<Unit>()
        var submitted = false
        val request = async(start = CoroutineStart.LAZY) {
            val ownJob = coroutineContext[Job]!!
            check(gate.begin(ownJob))
            try {
                entered.complete(Unit)
                awaitRuntimeReady(states, 7)
                submitted = true
            } finally { gate.finish(ownJob) }
        }
        request.start(); entered.await(); gate.cancel(); request.cancelAndJoin()
        states.value = waiting.copy(rootState = RuntimeRootState.READY)
        assertFalse(submitted)
        assertTrue(request.isCancelled)
        assertTrue(gate.begin(Job()))
    }

    @Test fun unknownEngineIsNotReadyEvenWithRootReady() {
        val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = 7,
            engineState = RuntimeEngineState.UNKNOWN, rootState = RuntimeRootState.READY)
        assertEquals(RuntimeStartReadiness.WAITING, runtimeStartReadiness(state, 7))
        assertEquals(RuntimeStartReadiness.READY, runtimeStartReadiness(state.copy(engineState = RuntimeEngineState.IDLE), 7))
    }

    @Test fun staleSessionFailureAndBusyStatesRejectBeforePreparing() = runBlocking {
        val ready = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = 7,
            engineState = RuntimeEngineState.IDLE, rootState = RuntimeRootState.READY)
        for (state in listOf(ready.copy(sessionGeneration = 8), ready.copy(rootState = RuntimeRootState.FAILED),
            ready.copy(engineState = RuntimeEngineState.RUNNING), ready.copy(engineState = RuntimeEngineState.PAUSED),
            ready.copy(engineState = RuntimeEngineState.STOPPING))) {
            assertEquals(RuntimeStartReadiness.REJECTED, awaitRuntimeReady(MutableStateFlow(state), 7, 100))
        }
        assertEquals(RuntimeStartReadiness.TIMEOUT, awaitRuntimeReady(MutableStateFlow(ready.copy(rootState = RuntimeRootState.STARTING)), 7, 1))
        assertEquals(RuntimeStartReadiness.READY, awaitRuntimeReady(MutableStateFlow(ready), 7, 100))
    }

    @Test fun repeatedCancelAndRetryDoesNotReleaseNewOwner() {
        val gate = RuntimeStartGate()
        repeat(30) {
            val first = Job(); val retry = Job()
            assertTrue(gate.begin(first))
            gate.cancel()
            assertTrue(first.isCancelled)
            // The preparing coroutine owns the gate until its cleanup/finally completes.
            assertFalse(gate.begin(retry))
            gate.finish(first)
            assertTrue(gate.begin(retry))
            gate.finish(first)
            assertFalse(gate.begin(Job()))
            gate.finish(retry)
        }
    }
}
