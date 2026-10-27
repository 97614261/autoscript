package com.autoscript.studio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorTreeBrowsingTest {
    private fun node(id: String, depth: Int = 0, slots: List<String> = emptyList(),
        kind: String = "task.noop", slot: String? = null, expanded: Boolean = true) =
        EditorTreeNode(id, id, kind, depth, slots.isNotEmpty(), expanded, childSlots = slots, childSlot = slot)

    private fun tree() = listOf(node("outer", slots = listOf("body")),
        node("before", 1, slot = "body"), node("inner", 1, listOf("then", "else"), "control.if", "body"),
        node("leaf", 2, slot = "then"), node("after", 1, slot = "body"), node("root"))

    @Test fun focusRebasesOnlyTheViewAndRetainsActualBranchesAndIdentities() {
        val nodes = tree()
        val original = nodes.toList()
        val focused = editorFocusedTreeNodes(nodes, "inner")
        assertEquals(listOf("inner", "leaf"), focused.map { it.id })
        assertEquals(listOf(0, 1), focused.map { it.depth })
        assertEquals(original, nodes)
        assertEquals(listOf("then", "else"), focused.first().childSlots)
        val rows = editorTreeDisplayRows(focused)
        assertEquals(listOf("then", "else"), rows.filterIsInstance<EditorTreeDisplayRow.Branch>().map { it.slot })
        assertEquals(listOf("inner", "leaf"), rows.mapNotNull { it.nodeId })
        assertTrue(rows.filterIsInstance<EditorTreeDisplayRow.Branch>().last().empty)
    }

    @Test fun focusNavigationUsesActualAncestorsNotSiblingsOrInternalIds() {
        val nodes = tree().map { if (it.id == "inner") it.copy(label = "判断 · 参数") else it }
        assertEquals(listOf("outer", "inner"), editorFocusPath(nodes, "inner").map { it.id })
        assertEquals(listOf("outer", "判断"), editorFocusPath(nodes, "inner").map { it.label })
        assertEquals("inner", editorFocusTarget(nodes, "inner"))
        assertEquals("inner", editorFocusTarget(nodes, "leaf"))
        assertEquals("outer", editorFocusTarget(nodes, "after"))
        assertNull(editorFocusTarget(nodes, "root"))
        assertNull(editorFocusTarget(nodes, "missing"))
    }

    @Test fun deletedLeafOrMissingFocusSafelyReturnsOriginalTree() {
        val nodes = tree()
        for (id in listOf(null, "missing", "leaf")) {
            assertSame(nodes, editorFocusedTreeNodes(nodes, id))
            assertTrue(editorFocusPath(nodes, id).isEmpty())
        }
        assertEquals(listOf(0, 1, 1, 2, 1), editorFocusedTreeNodes(nodes, "outer").map { it.depth })
    }

    @Test fun foldedContainersCanBeFocusedWithoutDiscardingHiddenChildren() {
        val nodes = tree().map { if (it.id == "inner") it.copy(expanded = false) else it }
        val focused = editorFocusedTreeNodes(nodes, "inner")
        assertEquals(2, focused.size)
        assertEquals(listOf("inner"), editorTreeDisplayRows(focused).mapNotNull { it.nodeId })
        assertEquals(EditorSelectionImpact(1, 2), editorSelectionImpact(nodes, setOf("inner")))
    }

    @Test fun selectionImpactCountsUnionAcrossParentsSiblingsAndOutsideFocus() {
        val nodes = tree()
        assertEquals(EditorSelectionImpact(0, 0), editorSelectionImpact(nodes, setOf("deleted", "branch:inner:then")))
        assertEquals(EditorSelectionImpact(1, 5), editorSelectionImpact(nodes, setOf("outer")))
        assertEquals(EditorSelectionImpact(3, 5), editorSelectionImpact(nodes, setOf("outer", "inner", "leaf")))
        assertEquals(EditorSelectionImpact(3, 4), editorSelectionImpact(nodes, setOf("before", "inner", "root")))
        assertEquals(EditorSelectionImpact(6, 6), editorSelectionImpact(nodes, nodes.map { it.id }.toSet()))
        assertEquals(EditorSelectionImpact(2, 3), editorSelectionImpact(nodes, setOf("inner", "root")))
    }

    @Test fun unchangedVisibleSelectionNeverRequestsAScroll() {
        for (target in 4..7) assertNull(editorMinimumScrollRequest(target, listOf(4, 5, 6, 7), 120, 30))
        assertNull(editorMinimumScrollRequest(-1, emptyList(), 0, 0))
        assertEquals(EditorListScrollRequest(1, 0), editorMinimumScrollRequest(1, listOf(4, 5, 6), 120, 30))
        assertEquals(EditorListScrollRequest(9, -90), editorMinimumScrollRequest(9, listOf(4, 5, 6), 120, 30))
    }

    @Test fun firstLayoutAndShortViewportsUseSafeBoundedOffsets() {
        assertEquals(EditorListScrollRequest(8, 0), editorMinimumScrollRequest(8, emptyList(), 0, 0))
        assertEquals(EditorListScrollRequest(8, 0), editorMinimumScrollRequest(8, listOf(4), 20, 30))
        assertEquals(EditorListScrollRequest(8, -19), editorMinimumScrollRequest(8, listOf(4), 20, 0))
    }

    @Test fun manualDetachmentGatesCrossPluginAndPausedFollowing() {
        val available = setOf("main", "worker")
        val running = VisualDebugPosition("worker", "leaf")
        val paused = VisualDebugPosition("main", "loop")
        assertEquals("worker", editorFollowFlow(true, running, available))
        assertNull(editorFollowFlow(false, running, available))
        assertNull(editorFollowFlow(false, paused, available))
        assertEquals("main", editorFollowFlow(true, paused, available))
        assertNull(editorFollowFlow(true, VisualDebugPosition("removed", "leaf"), available))
        assertNull(editorFollowFlow(true, null, available))
    }

    @Test fun disabledSelectionUsesRootFlagsNotInheritedChildFlags() {
        val nodes = listOf(EditorProgramNode("parent", "parent", "control.repeat", 0, disabled = true),
            EditorProgramNode("child", "child", "task.noop", 1, inheritedDisabled = true),
            EditorProgramNode("root", "root", "task.noop", 0))
        assertTrue(editorSelectionOwnDisabled(nodes, setOf("parent", "child")))
        assertFalse(editorSelectionOwnDisabled(nodes, setOf("child")))
        assertFalse(editorSelectionOwnDisabled(nodes, setOf("parent", "root")))
        assertFalse(editorSelectionOwnDisabled(nodes, emptySet()))
        assertFalse(editorSelectionOwnDisabled(nodes, setOf("deleted")))
    }

    @Test fun productionWiringRetainsFullTreeForActionsAndWarnsAboutOutsideSelection() {
        val source = editorDockTestSources()
        assertTrue(source.contains("nodes = displayedTreeNodes"))
        assertTrue(source.contains("previewNodes = displayedTreeNodes"))
        assertTrue(source.contains("editorSelectionImpact(nodes, checkedIds)"))
        assertTrue(source.contains("范围外还选中"))
        assertTrue(source.contains("if (value) focusedNodeId = null"))
        val screen = File("src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt").readText()
        assertTrue(screen.contains("editorFollowFlow(followExecution, executionPosition ?: stepState.position, editors.keys)"))
        assertTrue(screen.contains("programFollowExecution = followExecution"))
        assertFalse(screen.contains("val programNodes = remember(editor, editorRevision)"))
    }
}
