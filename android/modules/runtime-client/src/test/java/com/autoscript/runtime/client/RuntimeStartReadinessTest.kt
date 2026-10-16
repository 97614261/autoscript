package com.autoscript.runtime.client

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeStartReadinessTest {
    private val starting = RuntimeConnectionState(
        phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = 7L,
        engineState = RuntimeEngineState.IDLE, rootState = RuntimeRootState.STARTING,
    )

    @Test fun firstRequestWaitsForRootCallback() = runBlocking {
        val states = MutableStateFlow(starting)
        val request = async(start = CoroutineStart.UNDISPATCHED) { awaitRuntimeReady(states, 7L) }
        assertFalse(request.isCompleted)
        states.value = starting.copy(rootState = RuntimeRootState.READY)
        assertEquals(RuntimeStartReadiness.READY, request.await())
    }

    @Test fun rootFailureDisconnectAndReplacedSessionReject() {
        listOf(
            starting.copy(rootState = RuntimeRootState.FAILED),
            starting.copy(phase = RuntimeConnectionPhase.DISCONNECTED),
            starting.copy(sessionGeneration = 8L),
            starting.copy(rootState = RuntimeRootState.READY, engineState = RuntimeEngineState.PAUSED),
        ).forEach { assertEquals(RuntimeStartReadiness.REJECTED, runtimeStartReadiness(it, 7L)) }
    }

    @Test fun waitingIsBoundedAndCancellable() = runBlocking {
        val states = MutableStateFlow(starting)
        assertEquals(RuntimeStartReadiness.TIMEOUT, awaitRuntimeReady(states, 7L, timeoutMillis = 1L))
        val request = async(start = CoroutineStart.UNDISPATCHED) { awaitRuntimeReady(states, 7L) }
        request.cancelAndJoin()
        states.value = starting.copy(rootState = RuntimeRootState.READY)
        assertTrue(request.isCancelled)
    }
}
