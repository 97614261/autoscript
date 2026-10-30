package com.autoscript.runtime.client

import com.autoscript.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeExecutionEpochTest {
    private val idle = RuntimeConnectionState(RuntimeConnectionPhase.CONNECTED, sessionGeneration = 1,
        engineState = RuntimeEngineState.IDLE, rootState = RuntimeRootState.READY)
    @Test fun ordinaryRefreshAndRootDiagnosticDoNotInvalidateDraftWork() {
        val epoch = RuntimeExecutionEpoch(); epoch.observe(idle); val original = epoch.revision
        epoch.observe(idle.copy(stateRevision = 20, message = "refreshed"))
        epoch.observe(idle.copy(stateRevision = 21, rootState = RuntimeRootState.UNKNOWN))
        assertEquals(original, epoch.revision)
    }
    @Test fun transientRunAndReturnToIdleStillInvalidates() {
        val epoch = RuntimeExecutionEpoch(); epoch.observe(idle); val original = epoch.revision
        epoch.observe(idle.copy(engineState = RuntimeEngineState.RUNNING)); epoch.observe(idle)
        assertEquals(original + 2, epoch.revision)
    }
    @Test fun reconnectOrNewSessionInvalidatesEvenWithSameEngineState() {
        val epoch = RuntimeExecutionEpoch(); epoch.observe(idle); val original = epoch.revision
        epoch.observe(idle.copy(phase = RuntimeConnectionPhase.DISCONNECTED)); epoch.observe(idle)
        epoch.observe(idle.copy(sessionGeneration = 2))
        assertEquals(original + 3, epoch.revision)
    }
}
