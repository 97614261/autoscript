package com.autoscript.studio

import com.autoscript.core.model.*
import org.junit.Assert.*
import org.junit.Test

class InputFeatureSnapshotTest {
    private val ready = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
        sessionGeneration = 7, rootState = RuntimeRootState.READY)
    @Test fun replacedDisconnectedAndRefreshingSessionsInvalidateCachedFeaturesImmediately() {
        val snapshot = InputFeatureSnapshot(7, 0, 3)
        assertEquals(3, snapshot.available(ready, 0))
        assertNull(snapshot.available(ready.copy(sessionGeneration = 8), 0))
        assertNull(snapshot.available(ready.copy(phase = RuntimeConnectionPhase.DISCONNECTED), 0))
        assertNull(snapshot.available(ready.copy(rootState = RuntimeRootState.STARTING), 0))
        assertNull(snapshot.available(ready, 1))
        assertNull(InputFeatureSnapshot(null, 0, 3).available(ready.copy(sessionGeneration = null), 0))
    }
    @Test fun unrelatedUiMessagesDoNotInventNewBackendSupport() {
        assertEquals(1, InputFeatureSnapshot(7, 0, 1).available(ready.copy(message = "运行中", stateRevision = 12), 0))
    }
    @Test fun stalePickerCompletionCannotUnhideTheNextPreparation() {
        val state = InputPickerLaunchState()
        val old = state.begin()
        assertTrue(state.preparing)
        state.invalidate()
        val fresh = state.begin()
        assertFalse(state.current(old))
        state.finish(old)
        assertTrue(state.preparing)
        state.finish(fresh)
        assertFalse(state.preparing)
    }
}
