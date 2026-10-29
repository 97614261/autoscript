package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorBranchFoldingTest {
    private fun node(id: String, depth: Int = 0, kind: String = "task.noop", slots: List<String> = emptyList(),
        slot: String? = null, expanded: Boolean = true) =
        EditorTreeNode(id, id, kind, depth, slots.isNotEmpty(), expanded, childSlots = slots, childSlot = slot)

    private fun tree() = listOf(
        node("if", kind = "control.if", slots = listOf("then", "else")),
        node("call", 1, "flow.call.branch", listOf("success", "failure"), "then"),
        node("good", 2, slot = "success"), node("bad", 2, slot = "failure"),
        node("otherwise", 1, slot = "else"), node("root"),
    )

    @Test fun eachConditionalAndResultBranchFoldsIndependentlyWithoutLosingItsAnchor() {
        val nodes = tree()
        for ((key, hidden) in mapOf("branch:if:then" to setOf("call", "good", "bad"),
            "branch:if:else" to setOf("otherwise"), "branch:call:success" to setOf("good"),
            "branch:call:failure" to setOf("bad"))) {
            val display = editorTreeDisplayRows(nodes, setOf(key))
            val branch = display.filterIsInstance<EditorTreeDisplayRow.Branch>().single { it.key == key }
            assertFalse(branch.expanded)
            assertEquals(hidden.size, branch.descendantCount)
            assertEquals(nodes.map { it.id }.filterNot { it in hidden }, display.mapNotNull { it.nodeId })
            assertTrue(display.filterIsInstance<EditorTreeDisplayRow.Branch>().filter { it.key != key }.all { it.expanded })
        }
    }

    @Test fun outerFoldAndRestoreRetainTheNestedBranchFoldAndOriginalNodeObjects() {
        val nodes = tree()
        val original = nodes.toList()
        val nestedKey = editorBranchKey("call", "failure")
        val both = setOf(editorBranchKey("if", "then"), nestedKey)
        assertEquals(listOf("if", "otherwise", "root"), editorTreeDisplayRows(nodes, both).mapNotNull { it.nodeId })
        val restored = editorTreeDisplayRows(nodes, setOf(nestedKey))
        assertEquals(listOf("if", "call", "good", "otherwise", "root"), restored.mapNotNull { it.nodeId })
        restored.filterIsInstance<EditorTreeDisplayRow.Node>().forEach { row ->
            assertSame(nodes.first { it.id == row.nodeId }, row.node)
        }
        assertEquals(original, nodes)
        assertEquals(nodes.map { it.id }, editorTreeDisplayRows(nodes).mapNotNull { it.nodeId })
    }

    @Test fun collapsedBranchCountIncludesDescendantsHiddenByASeparateNodeFold() {
        val nodes = tree().map { if (it.id == "call") it.copy(expanded = false) else it }
        val display = editorTreeDisplayRows(nodes, setOf(editorBranchKey("if", "then")))
        val branch = display.filterIsInstance<EditorTreeDisplayRow.Branch>().first { it.slot == "then" }
        assertEquals(3, branch.descendantCount)
        assertFalse(branch.empty)
        assertEquals(listOf("if", "otherwise", "root"), display.mapNotNull { it.nodeId })
    }

    @Test fun locatingAHiddenNodeRevealsOnlyTheBranchesOnItsActualAncestorChain() {
        val nodes = tree().map { if (it.id == "call") it.copy(expanded = false) else it }
        val all = editorTreeBranchKeys(nodes)
        assertEquals(setOf("branch:if:then", "branch:if:else", "branch:call:success", "branch:call:failure"), all)
        assertEquals(setOf("branch:if:then", "branch:call:success"), editorBranchAncestorKeys(nodes, "good"))
        assertEquals(setOf("branch:if:then"), editorBranchAncestorKeys(nodes, "call"))
        assertEquals(setOf("branch:if:else"), editorBranchAncestorKeys(nodes, "otherwise"))
        for (id in listOf(null, "missing", "if", "root")) assertTrue(editorBranchAncestorKeys(nodes, id).isEmpty())
        val remaining = all - editorBranchAncestorKeys(nodes, "good")
        val visible = editorTreeDisplayRows(tree(), remaining)
        assertEquals(listOf("if", "call", "good", "root"), visible.mapNotNull { it.nodeId })
    }

    @Test fun emptyBranchesKeepTheirInsertionIdentityAndUnknownKeysCannotHideNodes() {
        val nodes = listOf(node("if", kind = "control.if", slots = listOf("then", "elseIf0", "else")),
            node("child", 1, slot = "elseIf0"), node("root"))
        val rows = editorTreeDisplayRows(nodes, setOf("branch:missing:then", "branch:if:then", "branch:if:elseIf0"))
        val branches = rows.filterIsInstance<EditorTreeDisplayRow.Branch>()
        assertEquals(listOf("then", "elseIf0", "else"), branches.map { it.slot })
        assertTrue(branches.filter { it.slot != "elseIf0" }.all { it.empty && it.descendantCount == 0 })
        assertEquals(1, branches.single { it.slot == "elseIf0" }.descendantCount)
        assertTrue(branches.all { it.nodeId == null })
        assertEquals(listOf("if", "root"), rows.mapNotNull { it.nodeId })
    }

    @Test fun foldedProjectionStillClosesGroupsAndInsertionPreviewUsesTheRealFullBranch() {
        val nodes = tree()
        val rows = editorTreeDisplayRows(nodes, setOf("branch:if:then"))
        val grouping = editorTreeGrouping(rows)
        val heading = grouping.rows.first { it.row.key == "branch:if:then" }
        assertFalse(heading.groups.any { it.key == heading.row.key })
        assertTrue(grouping.rows.last().groups.isEmpty())
        val preview = editorInsertionPreview(nodes, "call", EditorInsertPosition.INSIDE, "failure")
        assertTrue(preview.lines.any { it.label == "bad" })
        assertEquals(4, preview.lines.single { it.marker }.depth)
    }

    @Test fun bothEditorsShareBranchStateAndKeepFoldingSeparateFromInsertionSelection() {
        val source = editorDockTestSources()
        assertEquals(2, Regex("collapsedBranchKeys = collapsedProgramBranchKeys").findAll(source).count())
        assertEquals(2, Regex("onToggleBranchExpansion = ::toggleBranchExpansion").findAll(source).count())
        assertEquals(2, Regex("editorTreeDisplayRows\\(viewNodes, collapsedBranchKeys\\)").findAll(source).count())
        val heading = source.substringAfter("private fun EditorBranchHeading(").substringBefore("internal fun EditorProgramTreeRow(")
        assertTrue(heading.contains("onClick = onSelect"))
        assertTrue(heading.contains("onToggle?.invoke()"))
        assertTrue(heading.contains("!branch.empty && onToggle != null"))
    }
}
