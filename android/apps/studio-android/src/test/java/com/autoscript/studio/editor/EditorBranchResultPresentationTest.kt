package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeBranchOutcome
import com.autoscript.runtime.api.RuntimeExecutionFeedback
import com.autoscript.runtime.api.RuntimeNodePosition
import org.junit.Assert.*
import org.junit.Test

class EditorBranchResultPresentationTest {
    private fun search(id: String) = EditorTreeNode(id, id, "vision.findimage", 0, true, true,
        childSlots = listOf("success", "failure"))
    private fun outcome(id: String, slot: String) = RuntimeBranchOutcome("main", id, slot)
    private fun result(id: String, slot: String) = EditorBranchExecution(id, slot, if (slot == "success") "找到" else "未找到")

    @Test fun consecutiveSearchesKeepTheirOwnResultOnTheCorrectHeading() {
        val nodes = listOf(search("first"), search("second"))
        val results = editorBranchExecutions(nodes, listOf(outcome("first", "success"), outcome("second", "failure")))
        assertEquals(listOf("branch:first:success", "branch:second:failure"), results.map { it.key })
        assertEquals(listOf("找到", "未找到"), results.map { it.label })
        assertTrue(editorTreeDisplayRows(nodes).map { it.key }.containsAll(results.map { it.key }))
    }

    @Test fun repeatedOwnerReplacesItsOldBranchWithoutReplacingAnotherSearch() {
        val results = editorBranchExecutions(listOf(search("first"), search("second")),
            listOf(outcome("first", "success"), outcome("second", "success"), outcome("first", "failure")))
        assertEquals(listOf("branch:second:success", "branch:first:failure"), results.map { it.key })
        assertTrue(editorBranchExecutions(listOf(search("first")), listOf(outcome("missing", "success"))).isEmpty())
        assertTrue(editorBranchExecutions(listOf(search("first").copy(childSlots = emptyList())),
            listOf(outcome("first", "success"))).isEmpty())
        assertTrue(editorBranchExecutions(listOf(search("first"), search("first")),
            listOf(outcome("first", "success"))).isEmpty())
    }

    @Test fun anActualEmptyBranchMovesTheCursorOffItsOwnerButAnErrorDoesNot() {
        val report = RuntimeExecutionFeedback(7, 2, "project", 1, "ended", RuntimeNodePosition("main", "first"),
            null, listOf(outcome("first", "failure")))
        val position = VisualDebugPosition("main", "first", recent = true)
        assertEquals("failure", editorFeedbackBranch(report, position)?.slot)
        assertNull(editorFeedbackBranch(report.copy(phase = "failed", failure = report.position), position))
        assertNull(editorFeedbackBranch(report.copy(position = RuntimeNodePosition("main", "second")), position))
        assertNull(editorFeedbackBranch(report, VisualDebugPosition("another", "first")))
        assertNull(editorFeedbackBranch(report.copy(branches = emptyList()), position))
    }

    @Test fun followingRevealsEachNewResultEvenWhenTheNextCheckpointAlreadyExists() {
        val tracker = EditorExecutionFollowTracker()
        val first = result("first", "success")
        val second = result("second", "failure")
        assertEquals("node:second", tracker.update("second", emptyList(), null, 0)?.key)
        assertEquals(first.key, tracker.update("second", listOf(first), null, 0)?.key)
        assertEquals(first.key, tracker.update("second", listOf(first), null, 0)?.key)
        assertEquals(second.key, tracker.update("after", listOf(first, second), null, 0)?.key)
        assertEquals("node:after", tracker.update("after", listOf(first, second), null, 1)?.key)
        assertEquals("node:next", tracker.update("next", listOf(first, second), null, 1)?.key)
    }

    @Test fun rerunningAndFailureNeverFollowAnOldResultAsIfItJustExecuted() {
        val tracker = EditorExecutionFollowTracker()
        val first = result("first", "success")
        val second = result("second", "failure")
        tracker.update("second", listOf(first, second), second, 0)
        assertEquals("node:second", tracker.update("second", listOf(first), null, 0)?.key)
        assertEquals(second.key, tracker.update("after", listOf(first, second), null, 0)?.key)
        assertEquals("node:error", tracker.update("error", listOf(first, second), null, 0, failed = true)?.key)
        assertNull(tracker.update(null, emptyList(), null, 0))
    }

    @Test fun executionUsesOneMarkerWithoutHighlightingTheOwnerOrPreviousSearch() {
        val nodes = listOf(search("first"), EditorTreeNode("tap", "tap", "input.click", 1, false, true,
            childSlot = "success"), search("second"))
        val tracker = EditorExecutionFollowTracker()
        val first = result("first", "success")
        val second = result("second", "failure")
        val firstMarker = editorExecutionMarker(nodes, tracker.update("second", listOf(first), null, 0))
        assertEquals(first.key, firstMarker?.key)
        val secondMarker = editorExecutionMarker(nodes, tracker.update("second", listOf(first, second), second, 0))
        assertEquals(second.key, secondMarker?.key)
        assertEquals(1, editorTreeDisplayRows(nodes).count { it.key == secondMarker?.key })
        assertNotEquals(firstMarker, secondMarker)
        val child = editorExecutionMarker(nodes, tracker.update("tap", listOf(first, second), null, 0))
        assertEquals("node:tap", child?.key)
        assertEquals(1, editorTreeDisplayRows(nodes).count { it.key == child?.key })
        assertFalse(editorBranchAncestorKeys(nodes, "tap").contains(child?.key))
    }

    @Test fun missingOrAmbiguousTargetsCannotAddAnExecutionMarker() {
        val nodes = listOf(search("first"))
        assertNull(editorExecutionMarker(nodes, null))
        assertNull(editorExecutionMarker(nodes, EditorExecutionTarget("missing")))
        assertNull(editorExecutionMarker(nodes, EditorExecutionTarget("first", "then")))
        assertNull(editorExecutionMarker(nodes + nodes, EditorExecutionTarget("first", "success")))
        assertEquals("node:first", editorExecutionMarker(nodes, EditorExecutionTarget("first"))?.key)
    }
}
