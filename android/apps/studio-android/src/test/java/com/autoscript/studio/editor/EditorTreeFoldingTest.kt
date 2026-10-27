package com.autoscript.studio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorTreeFoldingTest {
    private fun node(id: String, depth: Int, container: Boolean = false, expanded: Boolean = true) =
        EditorTreeNode(id, id, "task.noop", depth, container, expanded)

    @Test fun nestedCollapseHidesOnlyDescendantsAndKeepsSiblingRows() {
        val nodes = listOf(node("parent", 0, true), node("inner", 1, true, false),
            node("leaf", 2), node("sibling", 1), node("root", 0))
        assertEquals(listOf("parent", "inner", "sibling", "root"), editorVisibleTreeNodes(nodes).map { it.id })
        val collapsed = nodes.map { if (it.id == "parent") it.copy(expanded = false) else it }
        assertEquals(listOf("parent", "root"), editorVisibleTreeNodes(collapsed).map { it.id })
        val restored = collapsed.map { if (it.id == "parent") it.copy(expanded = true) else it }
        assertEquals(listOf("parent", "inner", "sibling", "root"), editorVisibleTreeNodes(restored).map { it.id })
        assertEquals(5, restored.size)
    }

    @Test fun locatingHiddenSelectionExpandsOnlyItsCollapsedAncestorChain() {
        val nodes = listOf(node("parent", 0, true, false), node("inner", 1, true, false),
            node("leaf", 2), node("other", 0, true, false), node("otherLeaf", 1))
        assertEquals(listOf("parent", "inner"), editorCollapsedAncestorIds(nodes, "leaf"))
        assertEquals(listOf("other"), editorCollapsedAncestorIds(nodes, "otherLeaf"))
        assertTrue(editorCollapsedAncestorIds(nodes, "parent").isEmpty())
        assertTrue(editorCollapsedAncestorIds(nodes, "missing").isEmpty())
        assertTrue(editorCollapsedAncestorIds(nodes, null).isEmpty())
    }

    @Test fun editListSharesMainTreeExpansionAndScrollsVisibleIndices() {
        val source = editorDockTestSources()
        assertTrue(source.contains("onToggleNode = ::toggleNodeExpansion"))
        assertTrue(source.contains("onToggleExpansion = ::toggleNodeExpansion"))
        assertTrue(source.contains("val index = displayedTreeNodes.indexOfFirst { it.id == programSelectedNodeId }"))
        val dialog = source.substringAfter("fun EditorNodeEditDialog(").substringBefore("fun editorTreeBreadcrumb(")
        assertTrue(dialog.contains("itemsIndexed(displayRows"))
        assertTrue(dialog.contains("editorTreeDisplayRows(viewNodes)"))
        assertTrue(dialog.contains("editorMinimumScrollRequest(visibleSelectedIndex"))
        assertTrue(dialog.contains("editListState.scrollToItem(request.index, request.offset)"))
        assertTrue(dialog.contains("onToggleExpansion(node.id)"))
        assertTrue(dialog.contains("onToggleNode?.invoke(node.id)"))
    }
}
