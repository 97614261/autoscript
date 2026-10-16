package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState

/** Origin-owned service return event; used by editor and configuration without observing unrelated runs. */
internal class EditorRunReturnGate {
    private var runningGeneration: Long? = null
    private var active = false
    private var lastRevision = -1L
    private var armed = false

    fun arm(state: RuntimeConnectionState) {
        armed = true
        active = false
        runningGeneration = state.sessionGeneration
        lastRevision = state.stateRevision
    }

    fun cancel() { armed = false; active = false }

    fun observe(state: RuntimeConnectionState): Boolean {
        if (!armed) return false
        if (state.stateRevision <= lastRevision) return false
        lastRevision = state.stateRevision
        if (state.phase != RuntimeConnectionPhase.CONNECTED || state.sessionGeneration != runningGeneration) {
            val wasActive = active
            cancel()
            return wasActive
        }
        if (state.phase == RuntimeConnectionPhase.CONNECTED && state.engineState in setOf(
                RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) {
            active = true
            return false
        }
        if (active && state.engineState in setOf(RuntimeEngineState.IDLE, RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED)) {
            cancel()
            return true
        }
        return false
    }
}
