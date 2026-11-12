package com.autoscript.runtime.service

import com.autoscript.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeExecutionFeedbackTest {
    private fun step(node: String) = """{"version":1,"kind":"step","flowId":"main","nodeId":"$node","phase":"enter"}"""
    private fun branch(node: String, slot: String) = """{"version":1,"kind":"branch","flowId":"main","nodeId":"$node","slot":"$slot"}"""

    @Test fun terminalReportRetainsActualEmptyBranchAndRoundTripsWithoutLogText() {
        val buffer = RuntimeExecutionFeedbackBuffer()
        assertTrue(buffer.observe(step("search")))
        assertTrue(buffer.observe(branch("search", "failure")))
        val ended = buffer.snapshot(7, 2, "project", "ended")
        assertEquals(RuntimeNodePosition("main", "search"), ended.position)
        assertEquals(listOf(RuntimeBranchOutcome("main", "search", "failure")), ended.branches)
        assertEquals(ended, RuntimeExecutionFeedback.decode(7, 2, "project", ended.encode()))
        assertFalse(ended.encode().contains("variables"))
    }

    @Test fun failureUsesItsOwnTypedIdentityEvenWhenTheStepBufferWasIncomplete() {
        val buffer = RuntimeExecutionFeedbackBuffer()
        buffer.observe(step("old"))
        buffer.observe("""{"version":1,"kind":"task","flowId":"worker","nodeId":"failed","status":"failed"}""")
        buffer.observe("""{"version":1,"kind":"dropped","eventCount":4,"count":4}""")
        val failed = buffer.snapshot(7, 2, "project", "failed")
        assertEquals(RuntimeNodePosition("worker", "failed"), failed.failure)
        assertTrue(failed.incomplete)
        assertNull(RuntimeExecutionFeedbackBuffer().snapshot(7, 3, "project", "running").failure)
    }

    @Test fun branchResultsAreLatestPerOwnerBoundedAndDoNotMixRuns() {
        val buffer = RuntimeExecutionFeedbackBuffer()
        repeat(100) { buffer.observe(branch("n$it", "success")) }
        buffer.observe(branch("n99", "failure"))
        val report = buffer.snapshot(7, 2, "project", "paused")
        assertEquals(32, report.branches.size)
        assertEquals("n68", report.branches.first().nodeId)
        assertEquals("failure", report.branches.last().slot)
        assertTrue(buffer.snapshot(7, 2, "project", "ended").sequence > report.sequence)
        val fresh = RuntimeExecutionFeedbackBuffer().snapshot(7, 3, "project", "running")
        assertTrue(fresh.branches.isEmpty()); assertNull(fresh.position)
    }

    @Test fun arbitraryLogsAndMalformedOrOversizedPayloadsCannotSupplyLocations() {
        val buffer = RuntimeExecutionFeedbackBuffer()
        assertFalse(buffer.observe("private text"))
        assertFalse(buffer.observe(branch("n1", "unexpected")))
        assertFalse(buffer.observe(step("x".repeat(129))))
        assertFalse(buffer.observe("""{"version":2,"kind":"step","flowId":"main","nodeId":"n1","phase":"enter"}"""))
        assertNull(RuntimeExecutionFeedback.decode(7, 2, "project", "x".repeat(32_769)))
        val encoded = buffer.snapshot(7, 2, "project", "running").encode()
        assertNull(RuntimeExecutionFeedback.decode(7, 0, "project", encoded))
        assertNull(RuntimeExecutionFeedback.decode(7, 2, "project", encoded.replace("running", "invented")))
    }

    @Test fun scriptOutputLossDoesNotClaimThatStructuralEvidenceWasLost() {
        val buffer = RuntimeExecutionFeedbackBuffer()
        buffer.observe("""{"version":1,"kind":"dropped","eventCount":0,"scriptLogCount":10,"count":10}""")
        assertFalse(buffer.snapshot(7, 2, "project", "ended").incomplete)
    }

    @Test fun consecutiveSearchResultsSurviveTheNextCheckpointAndRoundTripIndividually() {
        val buffer = RuntimeExecutionFeedbackBuffer()
        buffer.observe(step("first")); buffer.observe(branch("first", "success"))
        buffer.observe(step("second")); buffer.observe(branch("second", "failure"))
        buffer.observe(step("after"))
        val report = buffer.snapshot(7, 2, "project", "paused")
        assertEquals(listOf(RuntimeBranchOutcome("main", "first", "success"),
            RuntimeBranchOutcome("main", "second", "failure")), report.branches)
        assertEquals(report, RuntimeExecutionFeedback.decode(7, 2, "project", report.encode()))
        assertEquals("after", report.position?.nodeId)
    }

    @Test fun enteringTheSameSearchAgainClearsOnlyItsPreviousOutcome() {
        val buffer = RuntimeExecutionFeedbackBuffer()
        buffer.observe(branch("first", "success")); buffer.observe(branch("second", "failure"))
        buffer.observe(step("second"))
        assertEquals(listOf(RuntimeBranchOutcome("main", "first", "success")),
            buffer.snapshot(7, 2, "project", "paused").branches)
        buffer.observe(branch("second", "success"))
        assertEquals("success", buffer.snapshot(7, 2, "project", "paused").branches.last().slot)
        buffer.observe("""{"version":1,"kind":"trace","flowId":"main","nodeId":"second","phase":"enter"}""")
        assertEquals(1, buffer.snapshot(7, 2, "project", "running").branches.size)
    }
}
