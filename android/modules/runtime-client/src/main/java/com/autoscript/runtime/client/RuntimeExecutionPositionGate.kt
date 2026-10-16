package com.autoscript.runtime.client

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeExecutionPosition

internal fun acceptsExecutionPosition(position: RuntimeExecutionPosition, state: RuntimeConnectionState,
    endedRevision: Long, current: RuntimeExecutionPosition?): Boolean =
    state.phase == RuntimeConnectionPhase.CONNECTED && state.engineState == RuntimeEngineState.RUNNING &&
        state.sessionGeneration == position.sessionGeneration && position.runRevision > endedRevision &&
        position.runRevision >= (current?.runRevision ?: 0) &&
        (current?.runRevision != position.runRevision || position.sampleSequence >= current.sampleSequence)
