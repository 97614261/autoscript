package com.autoscript.studio

import com.autoscript.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

class EditorExecutionEvidenceTest {
    @Test fun editingInvalidatesTheFrozenEvidenceEvenAfterUndo() {
        val original = mapOf("main" to "original", "worker" to "other")
        val lease = EditorExecutionEvidenceLease(original)
        assertTrue(lease.accepts(original.toMap()))
        assertFalse(lease.accepts(original + ("worker" to "changed")))
        assertFalse(lease.accepts(original))
        assertTrue(EditorExecutionEvidenceLease(original).accepts(original))
        val tracker = EditorExecutionEvidenceTracker()
        assertTrue(tracker.accepts(1, original))
        assertFalse(tracker.accepts(1, original + ("main" to "edited")))
        assertFalse(tracker.accepts(1, original))
        assertTrue(tracker.accepts(2, original))
        assertFalse(tracker.accepts(1, original))
    }

    @Test fun failureNeverUsesTheLastUnrelatedSampleAsAnErrorLocation() {
        val report = RuntimeExecutionFeedback(7, 2, "project", 1, "failed",
            RuntimeNodePosition("main", "old"), null, emptyList())
        assertNull(editorFeedbackPosition(report))
        assertEquals("actual", editorFeedbackPosition(report.copy(failure = RuntimeNodePosition("worker", "actual")))?.nodeId)
        assertEquals("old", editorFeedbackPosition(report.copy(phase = "ended"))?.nodeId)
    }

    @Test fun resultLabelsDistinguishRecognitionPluginAndConditionalOutcomes() {
        fun outcome(slot: String) = RuntimeBranchOutcome("main", "n1", slot)
        assertEquals("找到", editorBranchOutcomeLabel("vision.findimage", outcome("success")))
        assertEquals("未找到", editorBranchOutcomeLabel("vision.findallimages", outcome("failure")))
        assertEquals("成功", editorBranchOutcomeLabel("flow.call.branch", outcome("success")))
        assertEquals("失败", editorBranchOutcomeLabel("flow.call.branch", outcome("failure")))
        assertEquals("满足", editorBranchOutcomeLabel("control.if", outcome("then")))
        assertEquals("否则", editorBranchOutcomeLabel("control.if", outcome("else")))
    }

    @Test fun stepDisplaySurvivesTheRequestAndReadGapButCannotAuthorizeAnotherStep() {
        val state = VisualStepState()
        state.paused(VisualDebugPosition("main", "first"))
        assertTrue(state.begin()); state.running()
        assertEquals("first", state.displayPosition?.nodeId)
        assertNull(state.position); assertFalse(state.begin())
        val ticket = state.fetching()
        assertEquals("first", state.displayPosition?.nodeId)
        assertFalse(state.begin())
        val reply = RuntimeDebugReply(RuntimeDebugReply.SUCCESS, "main", "next", emptyList(), runtimeState = RuntimeProtocol.STATE_PAUSED)
        assertTrue(state.fetched(ticket, reply))
        assertEquals("next", state.displayPosition?.nodeId)
        state.reset(); assertNull(state.displayPosition)
        assertFalse(state.fetched(ticket, reply))
    }

    @Test fun continuationAndFailedSnapshotClearTheHeldStepDisplay() {
        val state = VisualStepState()
        state.paused(VisualDebugPosition("main", "first"))
        assertTrue(state.begin(true)); state.running()
        assertNull(state.displayPosition)
        state.paused(VisualDebugPosition("main", "second"))
        state.fetchFinished(state.fetching())
        assertNull(state.displayPosition)
    }
}
