package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import org.junit.Assert.*
import org.junit.Test

class EditorRunReturnGateTest {
    private fun state(engine: RuntimeEngineState, revision: Long, generation: Long = 1L) = RuntimeConnectionState(
        phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = generation, engineState = engine, stateRevision = revision)

    @Test fun replacementBeforeOwnRunCannotAttachToUnrelatedExecution() {
        val gate = EditorRunReturnGate()
        gate.arm(state(RuntimeEngineState.IDLE, 1, -8))
        assertFalse(gate.observe(state(RuntimeEngineState.RUNNING, 2, -9)))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 3, -9)))
        gate.arm(state(RuntimeEngineState.IDLE, 4, -9))
        assertFalse(gate.observe(state(RuntimeEngineState.RUNNING, 5, -9)))
        assertTrue(gate.observe(state(RuntimeEngineState.STOPPED, 6, -9)))
    }

    @Test fun `initial stopped state and root updates do not reopen editor`() {
        val gate = EditorRunReturnGate()
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 1)))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 2)))
    }
    @Test fun `stop restores once and ignores stopping and duplicate terminal notifications`() {
        val gate = EditorRunReturnGate()
        gate.arm(state(RuntimeEngineState.IDLE, 0))
        assertFalse(gate.observe(state(RuntimeEngineState.RUNNING, 1)))
        assertFalse(gate.observe(state(RuntimeEngineState.PAUSED, 2)))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPING, 3)))
        assertTrue(gate.observe(state(RuntimeEngineState.STOPPED, 4)))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 4)))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 5)))
    }
    @Test fun `natural finish failure and a subsequent run each restore`() {
        val gate = EditorRunReturnGate()
        gate.arm(state(RuntimeEngineState.IDLE, 0))
        gate.observe(state(RuntimeEngineState.RUNNING, 1))
        assertTrue(gate.observe(state(RuntimeEngineState.IDLE, 2)))
        gate.arm(state(RuntimeEngineState.IDLE, 2))
        gate.observe(state(RuntimeEngineState.RUNNING, 3))
        assertTrue(gate.observe(state(RuntimeEngineState.FAILED, 4)))
    }
    @Test fun `disconnect and session replacement restore without polling`() {
        val gate = EditorRunReturnGate()
        gate.arm(state(RuntimeEngineState.IDLE, 0))
        gate.observe(state(RuntimeEngineState.RUNNING, 1))
        assertTrue(gate.observe(RuntimeConnectionState(phase = RuntimeConnectionPhase.DISCONNECTED, stateRevision = 2)))
        gate.arm(state(RuntimeEngineState.IDLE, 2))
        gate.observe(state(RuntimeEngineState.RUNNING, 3))
        assertTrue(gate.observe(state(RuntimeEngineState.IDLE, 4, 2)))
    }
    @Test fun `stale terminal state cannot dismiss a newer active run`() {
        val gate = EditorRunReturnGate()
        gate.arm(state(RuntimeEngineState.IDLE, 9))
        gate.observe(state(RuntimeEngineState.RUNNING, 10))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 9)))
        assertTrue(gate.observe(state(RuntimeEngineState.STOPPED, 11)))
    }
    @Test fun `another entry running and stopping cannot reopen an unarmed window`() {
        val gate = EditorRunReturnGate()
        assertFalse(gate.observe(state(RuntimeEngineState.RUNNING, 1)))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 2)))
    }
    @Test fun `closing or cancelling disarms late notifications`() {
        val gate = EditorRunReturnGate()
        gate.arm(state(RuntimeEngineState.IDLE, 0))
        gate.observe(state(RuntimeEngineState.RUNNING, 1))
        gate.cancel()
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 2)))
    }
    @Test fun `stopped and root preparation notifications cannot return before own run starts`() {
        val gate = EditorRunReturnGate()
        gate.arm(state(RuntimeEngineState.STOPPED, 10))
        assertFalse(gate.observe(state(RuntimeEngineState.STOPPED, 11)))
        assertFalse(gate.observe(state(RuntimeEngineState.IDLE, 12)))
        assertFalse(gate.observe(state(RuntimeEngineState.RUNNING, 13)))
        assertTrue(gate.observe(state(RuntimeEngineState.STOPPED, 14)))
    }
}
