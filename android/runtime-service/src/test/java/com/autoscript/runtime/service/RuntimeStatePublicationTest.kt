package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStatePublicationTest {
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
