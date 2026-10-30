package com.autoscript.runtime.client

import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeEngineState

/** Counts execution/session transitions even when a StateFlow collector misses a short run. */
internal class RuntimeExecutionEpoch {
    private var previous: Triple<RuntimeConnectionPhase, Long?, RuntimeEngineState>? = null
    @Volatile var revision = 0L
        private set

    @Synchronized fun observe(state: RuntimeConnectionState) {
        val stamp = Triple(state.phase, state.sessionGeneration, state.engineState)
        if (previous != stamp) { revision++; previous = stamp }
    }
}
