package com.autoscript.runtime.client

import com.autoscript.core.model.*
import com.autoscript.runtime.api.RuntimeExecutionFeedback
import org.junit.Assert.*
import org.junit.Test

class RuntimeExecutionFeedbackGateTest {
    private val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = 7)
    private fun report(run: Long = 1, sequence: Long = 1, phase: String = "running") =
        RuntimeExecutionFeedback(7, run, "project", sequence, phase, null, null, emptyList())

    @Test fun terminalCannotRegressToQueuedRunningOrOldRunButAnotherRunCanStart() {
        val ended = report(sequence = 3, phase = "ended")
        assertFalse(acceptsExecutionFeedback(report(sequence = 2), state, ended))
        assertFalse(acceptsExecutionFeedback(report(sequence = 4), state, ended))
        assertTrue(acceptsExecutionFeedback(report(sequence = 4, phase = "ended"), state, ended))
        assertTrue(acceptsExecutionFeedback(report(run = 2), state, ended))
        assertFalse(acceptsExecutionFeedback(ended, state, report(run = 2)))
    }

    @Test fun disconnectedOrDifferentGenerationCannotRestoreOldEvidence() {
        assertFalse(acceptsExecutionFeedback(report(), state.copy(phase = RuntimeConnectionPhase.DISCONNECTED), null))
        assertFalse(acceptsExecutionFeedback(report(), state.copy(sessionGeneration = 8), null))
        assertTrue(acceptsExecutionFeedback(report(phase = "ended"), state, null))
    }
}
