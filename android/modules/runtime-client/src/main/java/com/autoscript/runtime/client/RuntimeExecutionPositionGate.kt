package com.autoscript.runtime.client

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeExecutionPosition
import com.autoscript.runtime.api.RuntimeExecutionFeedback

internal fun acceptsExecutionFeedback(report: RuntimeExecutionFeedback, state: RuntimeConnectionState,
    previous: RuntimeExecutionFeedback?): Boolean =
    state.phase == RuntimeConnectionPhase.CONNECTED && state.sessionGeneration == report.sessionGeneration &&
        (previous == null || report.runRevision > previous.runRevision ||
            report.runRevision == previous.runRevision && report.sequence > previous.sequence &&
            (previous.phase !in setOf("ended", "stopped", "failed") || report.phase == previous.phase))

internal fun acceptsExecutionPosition(position: RuntimeExecutionPosition, state: RuntimeConnectionState,
    endedRevision: Long, current: RuntimeExecutionPosition?): Boolean =
    state.phase == RuntimeConnectionPhase.CONNECTED && state.engineState == RuntimeEngineState.RUNNING &&
        state.sessionGeneration == position.sessionGeneration && position.runRevision > endedRevision &&
        position.runRevision >= (current?.runRevision ?: 0) &&
        (current?.runRevision != position.runRevision || position.sampleSequence >= current.sampleSequence)
