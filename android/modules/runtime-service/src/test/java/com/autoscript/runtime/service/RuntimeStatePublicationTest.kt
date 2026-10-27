package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeStatePublicationTest {
    @Test fun terminalTransitionAfterPublishedReadStillSchedulesItsFinalObservation() {
        repeat(10_000) {
            var native = RuntimeProtocol.STATE_STOPPING
            val published = mutableListOf<Int>()
            var scheduled = 0
            fun observe(): Int {
                val state = native
                published += state
                native = RuntimeProtocol.STATE_STOPPED
                return state
            }
            observeRuntimeStop(::observe) { scheduled++ }
            assertEquals(1, scheduled)
            observeRuntimeStop(::observe) { scheduled++ }
            assertEquals(listOf(RuntimeProtocol.STATE_STOPPING, RuntimeProtocol.STATE_STOPPED), published)
            assertEquals(1, scheduled)
        }
    }

    @Test fun failedOrAlreadyTerminalStopsDoNotRescheduleOrGetRelabeled() {
        for (state in listOf(RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED, RuntimeProtocol.STATE_IDLE)) {
            var reads = 0
            var scheduled = false
            observeRuntimeStop({ reads++; state }) { scheduled = true }
            assertEquals(1, reads)
            assertFalse(scheduled)
        }
    }
    @Test fun immediateRestartFromTerminalListenerCannotBeClearedByPreviousFinalization() {
        var activeRun: String? = "previous"
        val phases = mutableListOf<String>()
        publishFinalizedRuntimeState(finalizeState = {
            phases += "end-previous-log"
            activeRun = null
        }) {
            assertEquals(null, activeRun)
            phases += "publish-terminal"
            activeRun = "next"
        }
        assertEquals(listOf("end-previous-log", "publish-terminal"), phases)
        assertEquals("next", activeRun)
    }

    @Test fun incompleteFinalizationDoesNotAdvertiseReadiness() {
        var published = false
        assertThrows(IllegalStateException::class.java) {
            publishFinalizedRuntimeState(finalizeState = { error("public fixture") }) { published = true }
        }
        assertFalse(published)
    }

    @Test fun shortStepPublishesNewPauseEvenWhenStateCodeIsUnchanged() {
        assertTrue(shouldPublishRuntimeState(RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.ROOT_READY,
            RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.ROOT_READY, true))
        assertFalse(shouldPublishRuntimeState(RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.ROOT_READY,
            RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.ROOT_READY, false))
    }
    @Test fun stateAndRootChangesRemainObservable() {
        assertTrue(shouldPublishRuntimeState(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.ROOT_READY,
            RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.ROOT_READY, false))
        assertTrue(shouldPublishRuntimeState(RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.ROOT_FAILED,
            RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.ROOT_READY, false))
    }
}
