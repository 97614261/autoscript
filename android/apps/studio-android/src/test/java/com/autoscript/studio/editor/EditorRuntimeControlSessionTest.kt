package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRuntimeControlSessionTest {
    private fun connected(generation: Long = -7L, engine: RuntimeEngineState = RuntimeEngineState.RUNNING) =
        RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = generation, engineState = engine)

    @Test fun openSuppressesExistingRunAndCloseHandsBackOnce() {
        val owner = EditorRuntimeControlSession()
        val changes = mutableListOf<Boolean>()
        val set: (Boolean) -> Boolean = { changes.add(it); true }
        owner.open(connected(), set)
        repeat(4) { owner.observe(connected(), set) }
        assertEquals(listOf(false), changes)
        owner.close(connected(), set)
        owner.close(connected(), set)
        owner.observe(connected(), set)
        assertEquals(listOf(false, true), changes)
    }

    @Test fun openingBeforeBindingSuppressesOnceConnected() {
        val owner = EditorRuntimeControlSession()
        val changes = mutableListOf<Boolean>()
        val set: (Boolean) -> Boolean = { changes.add(it); true }
        owner.open(RuntimeConnectionState(), set)
        assertTrue(changes.isEmpty())
        owner.observe(connected(), set)
        assertEquals(listOf(false), changes)
    }

    @Test fun reconnectAndNewGenerationMustSuppressAgain() {
        val owner = EditorRuntimeControlSession()
        val changes = mutableListOf<Boolean>()
        val set: (Boolean) -> Boolean = { changes.add(it); true }
        owner.open(connected(), set)
        owner.observe(RuntimeConnectionState(phase = RuntimeConnectionPhase.DISCONNECTED), set)
        owner.observe(connected(), set)
        owner.observe(connected(8), set)
        assertEquals(listOf(false, false, false), changes)
        owner.close(connected(8, RuntimeEngineState.PAUSED), set)
        assertEquals(listOf(false, false, false, true), changes)
    }

    @Test fun terminalStatesNeverRestoreControlBall() {
        for (state in RuntimeEngineState.entries.filter { it !in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED) }) {
            val changes = mutableListOf<Boolean>()
            val owner = EditorRuntimeControlSession()
            val set: (Boolean) -> Boolean = { changes.add(it); true }
            owner.open(connected(), set)
            owner.close(connected(engine = state), set)
            assertEquals(state.name, listOf(false), changes)
        }
    }

    @Test fun staleOrDisconnectedCloseCannotEnableNewSessionControl() {
        for (state in listOf(connected(9), RuntimeConnectionState(phase = RuntimeConnectionPhase.DISCONNECTED))) {
            val changes = mutableListOf<Boolean>()
            val owner = EditorRuntimeControlSession()
            val set: (Boolean) -> Boolean = { changes.add(it); true }
            owner.open(connected(), set)
            owner.close(state, set)
            assertEquals(listOf(false), changes)
        }
    }

    @Test fun failedHideRetriesButSynchronousErrorNotificationDoesNotReenter() {
        val owner = EditorRuntimeControlSession()
        val changes = mutableListOf<Boolean>()
        lateinit var set: (Boolean) -> Boolean
        set = {
            changes.add(it)
            owner.observe(connected(), set)
            changes.size > 1
        }
        owner.open(connected(), set)
        owner.observe(connected(), set)
        assertEquals(listOf(false, false), changes)
        owner.close(connected(), set)
        assertEquals(listOf(false, false, true), changes)
    }

    @Test fun zeroGenerationIsRejectedButNegativeGenerationIsSupported() {
        val owner = EditorRuntimeControlSession()
        val changes = mutableListOf<Boolean>()
        val set: (Boolean) -> Boolean = { changes.add(it); true }
        owner.open(connected(0), set)
        assertTrue(changes.isEmpty())
        owner.observe(connected(-1), set)
        owner.close(connected(-1), set)
        assertEquals(listOf(false, true), changes)
    }
}
