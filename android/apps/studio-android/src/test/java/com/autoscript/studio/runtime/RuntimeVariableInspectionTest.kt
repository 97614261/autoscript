package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeDebugValue
import com.autoscript.runtime.api.RuntimeProtocol
import org.junit.Assert.*
import org.junit.Test

class RuntimeVariableInspectionTest {
    private fun snapshot(generation: Long = 7, value: String = "12") = RuntimeDebugReply(
        RuntimeDebugReply.SUCCESS, "child", "next", listOf(RuntimeDebugValue("local", "score", "integer", value, declaredType = "integer")),
        RuntimeProtocol.STATE_PAUSED, generation, 1,
    )

    @Test fun aLateReadCannotRestoreValuesAfterResumeOrAnotherPause() {
        val state = RuntimeVariableInspectionState()
        val first = requireNotNull(state.begin())
        assertNull(state.begin())
        state.invalidate()
        assertFalse(state.complete(first, 7, snapshot()))
        assertTrue(state.reply.variables.isEmpty())
        val next = requireNotNull(state.begin())
        state.discard(first)
        assertTrue(state.loading)
        assertTrue(state.complete(next, 7, snapshot(value = "13")))
        assertEquals("13", state.reply.variables.single().value)
        assertFalse(state.loading)
    }

    @Test fun aDifferentConnectionCannotPublishRuntimeValues() {
        val state = RuntimeVariableInspectionState()
        assertTrue(state.complete(requireNotNull(state.begin()), 7, snapshot(generation = 8)))
        assertEquals(RuntimeDebugReply.SESSION_MISMATCH, state.reply.status)
        assertTrue(state.reply.variables.isEmpty())
    }

    @Test fun nilUnsupportedAndTruncationAreVisible() {
        val nil = RuntimeDebugValue("local", "unset", "nil", "", declaredType = "boolean")
        val unsupported = RuntimeDebugValue("local", "tableValue", "unsupported", "table")
        val truncated = RuntimeDebugValue("local", "text", "string", "partial", true)
        assertTrue(runtimeDebugValueText(nil).contains("未赋值"))
        assertTrue(runtimeDebugValueText(unsupported).contains("table"))
        assertTrue(runtimeDebugValueText(truncated).contains("截断"))
        assertTrue(runtimeDebugSummary(snapshot()).contains("真实值"))
        assertTrue(runtimeDebugSummary(snapshot().copy(runtimeState = RuntimeProtocol.STATE_RUNNING)).contains("最近检查点"))
    }
}
