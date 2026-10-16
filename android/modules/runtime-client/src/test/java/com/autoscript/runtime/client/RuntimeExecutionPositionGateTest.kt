package com.autoscript.runtime.client

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeExecutionPosition
import org.junit.Assert.*
import org.junit.Test

class RuntimeExecutionPositionGateTest {
    @Test fun boundedPendingMergePreservesFastRowsAndRejectsOldSamplesAndRuns() {
        fun position(sequence: Long, run: Long = 2, start: Int = 0) = RuntimeExecutionPosition(-7, run, "project-1", "main", "node-${start + 19}", sequence,
            List(20) { com.autoscript.runtime.api.RuntimeNodePosition("main", "node-${start + it}") })
        val merged = com.autoscript.runtime.api.mergeExecutionPositions(position(1), position(2, start = 20))
        assertEquals(32, merged.recent.size)
        assertEquals("node-8", merged.recent.first().nodeId)
        assertEquals("node-39", merged.recent.last().nodeId)
        assertEquals(merged, com.autoscript.runtime.api.mergeExecutionPositions(merged, position(1)))
        val nextRun = position(1, run = 3, start = 100)
        assertEquals(nextRun, com.autoscript.runtime.api.mergeExecutionPositions(merged, nextRun))
        val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = -7, engineState = RuntimeEngineState.RUNNING)
        assertFalse(acceptsExecutionPosition(position(1), state, 0, merged))
        assertTrue(acceptsExecutionPosition(position(3), state, 0, merged))
        assertThrows(IllegalArgumentException::class.java) { merged.copy(recent = List(33) { merged.recent.first() }) }
    }
    @Test fun negativeServiceGenerationsAreValidOpaqueIdentities() {
        for (generation in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val position = RuntimeExecutionPosition(generation, 1, "project-1", "main", "node-1")
            val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
                sessionGeneration = generation, engineState = RuntimeEngineState.RUNNING)
            assertTrue(acceptsExecutionPosition(position, state, 0, null))
            assertFalse(acceptsExecutionPosition(position, state.copy(sessionGeneration = 7), 0, null))
            assertFalse(acceptsExecutionPosition(position, state, 1, null))
            assertFalse(acceptsExecutionPosition(position, state.copy(engineState = RuntimeEngineState.STOPPED), 0, null))
        }
        assertThrows(IllegalArgumentException::class.java) { RuntimeExecutionPosition(0, 1, "project-1", "main", "node-1") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeExecutionPosition(-1, 0, "project-1", "main", "node-1") }
    }
    @Test fun runningAcceptsCurrentButNotEndedOrOlderRunOrDifferentGeneration() {
        val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = 4, engineState = RuntimeEngineState.RUNNING)
        val position = RuntimeExecutionPosition(4, 6, "project-1", "main", "node-1")
        assertTrue(acceptsExecutionPosition(position, state, 5, null))
        assertFalse(acceptsExecutionPosition(position, state, 6, null))
        assertFalse(acceptsExecutionPosition(position, state, 5, position.copy(runRevision = 7)))
        assertFalse(acceptsExecutionPosition(position, state.copy(sessionGeneration = 5), 0, null))
        assertFalse(acceptsExecutionPosition(position, state.copy(engineState = RuntimeEngineState.PAUSED), 0, null))
        assertFalse(acceptsExecutionPosition(position, state.copy(engineState = RuntimeEngineState.STOPPED), 0, null))
        assertFalse(acceptsExecutionPosition(position, state.copy(phase = RuntimeConnectionPhase.DISCONNECTED), 0, null))
    }
}
