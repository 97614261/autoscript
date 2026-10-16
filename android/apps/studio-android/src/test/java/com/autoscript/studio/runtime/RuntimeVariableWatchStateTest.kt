package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeDebugValue
import com.autoscript.runtime.api.RuntimeProtocol
import org.junit.Assert.*
import org.junit.Test

class RuntimeVariableWatchStateTest {
    private fun value(scope: String, text: String, type: String = "integer", truncated: Boolean = false) =
        RuntimeDebugValue(scope, "score", type, text, truncated)
    private fun sample(flow: String = "main", run: Long = 1, generation: Long = 7, global: String = "1", local: String = "1") =
        RuntimeDebugReply(RuntimeDebugReply.SUCCESS, flow, "node", listOf(value("global", global), value("local", local)),
            RuntimeProtocol.STATE_RUNNING, generation, run)

    @Test fun firstSampleIsBaselineAndChangeIsBoundedAndCumulative() {
        val state = RuntimeVariableWatchState()
        assertTrue(state.sample(sample()))
        assertTrue(state.changed.isEmpty())
        assertTrue(state.sample(sample(global = "2")))
        assertEquals(1, state.latestChanges.size)
        assertEquals(1, state.values("changed", "", null).size)
        state.sample(sample(global = "2"))
        assertTrue(state.latestChanges.isEmpty())
        assertEquals(1, state.changed.size)
    }
    @Test fun globalsCompareAcrossPluginsButLocalsNeverCompareBetweenPlugins() {
        val state = RuntimeVariableWatchState()
        state.sample(sample())
        state.sample(sample(flow = "child", global = "2", local = "99"))
        assertEquals(setOf(RuntimeVariableKey("global", "", "score")), state.changed)
        assertEquals(1, state.values("global", "", "main").size)
        assertTrue(state.values("local", "", "main").isEmpty())
        assertEquals("99", state.values("local", "SC", null).single().value)
    }
    @Test fun newRunOrSignedSessionResetsBaselineAndOldRunIsRejected() {
        val state = RuntimeVariableWatchState()
        state.sample(sample()); state.sample(sample(global = "2"))
        state.sample(sample(run = 2, global = "10"))
        assertTrue(state.changed.isEmpty())
        assertFalse(state.sample(sample(run = 1)))
        assertEquals(2L, state.reply.runRevision)
        state.sample(sample(generation = -7, global = "20"))
        assertTrue(state.changed.isEmpty())
        assertEquals(-7L, state.reply.sessionGeneration)
    }
    @Test fun truncationAndTypeChangesAreObservedAndMissingValuesArePruned() {
        val state = RuntimeVariableWatchState()
        val first = sample().copy(variables = listOf(value("global", "same", "string")))
        state.sample(first)
        state.sample(first.copy(variables = listOf(value("global", "same", "string", true))))
        assertEquals(1, state.changed.size)
        state.sample(first.copy(variables = listOf(value("global", "3", "integer"))))
        assertEquals(1, state.latestChanges.size)
        state.sample(first.copy(variables = emptyList()))
        assertTrue(state.changed.isEmpty())
    }
    @Test fun busyLabelsTheOldSampleButMismatchAndInvalidDuplicateClearIt() {
        val state = RuntimeVariableWatchState()
        state.sample(sample())
        state.sample(RuntimeDebugReply.unavailable(RuntimeDebugReply.BUSY))
        assertEquals(RuntimeDebugReply.BUSY, state.status)
        assertEquals(2, state.reply.variables.size)
        state.sample(RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH))
        assertTrue(state.reply.variables.isEmpty())
        assertFalse(state.sample(sample().copy(variables = listOf(value("global", "1"), value("global", "2")))))
        assertTrue(state.reply.variables.isEmpty())
    }
    @Test fun compactAndFullWindowBoundsSurviveRotationAndCollapsedMaximization() {
        assertEquals(RuntimeVariableWindowRect(40, 300, 260, 320), runtimeVariableWindowRect(300, 620, 260, 320, 500, 800, false, false, 32))
        assertEquals(RuntimeVariableWindowRect(0, 0, 300, 200), runtimeVariableWindowRect(300, 200, 260, 320, 40, 50, true, false, 32))
        assertEquals(RuntimeVariableWindowRect(40, 168, 260, 32), runtimeVariableWindowRect(300, 200, 260, 320, 40, 300, true, true, 32))
    }
    @Test fun periodicReadCanRetainLastSampleWithoutClearingTheList() {
        val state = RuntimeVariableInspectionState()
        state.complete(state.begin()!!, 7, sample())
        state.begin(keepReply = true)
        assertTrue(state.loading)
        assertEquals(2, state.reply.variables.size)
    }
}
