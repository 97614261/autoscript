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
    @Test fun activityReturnRestoresOnlyTheTaskBackgroundedByThisRequestOnce() {
        val state = InputPickerLaunchState()
        val ticket = state.begin()
        var hidden = 0
        var restored = 0
        state.background(ticket, { hidden++; true }, { restored++; true })
        state.finish(ticket)
        assertTrue(state.current(ticket))
        assertTrue(state.returned(ticket))
        assertTrue(state.returned(ticket))
        assertEquals(1, hidden)
        assertEquals(1, restored)
    }
    @Test fun overlayAndEarlyCompletionDoNotInventAnActivityReturn() {
        val state = InputPickerLaunchState()
        val ticket = state.begin()
        assertTrue(state.returned(ticket))
        assertFalse(state.preparing)
        assertTrue(state.current(ticket))
    }
    @Test fun rejectedTaskBackgroundDoesNotBringAnUnrelatedTaskForward() {
        val state = InputPickerLaunchState()
        val ticket = state.begin()
        state.background(ticket, { false }, { fail("task never left the foreground"); true })
        assertTrue(state.returned(ticket))
    }
    @Test fun invalidationDiscardsOldNavigationAndLateResultsDoNotConsumeTheNewReturn() {
        val state = InputPickerLaunchState()
        val old = state.begin()
        state.background(old, { true }, { fail("closed picker must not reopen its Activity"); true })
        state.invalidate()
        val fresh = state.begin()
        var restores = 0
        state.background(fresh, { true }, { restores++; true })
        state.background(old, { fail("stale picker must not background a task"); true }, { true })
        assertFalse(state.returned(old))
        assertTrue(state.preparing)
        assertTrue(state.returned(fresh))
        assertEquals(1, restores)
    }
    @Test fun failedReturnPreservesTheEditorInsteadOfThrowingOrLaunchingAFallbackPage() {
        val state = InputPickerLaunchState()
        val ticket = state.begin()
        var attempts = 0
        state.background(ticket, { true }, { attempts++; throw SecurityException("synthetic task restriction") })
        assertFalse(state.returned(ticket))
        assertFalse(state.preparing)
        assertTrue(state.returned(ticket))
        assertEquals(1, attempts)
    }
}
