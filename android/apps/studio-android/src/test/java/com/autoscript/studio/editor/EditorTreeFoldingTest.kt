package com.autoscript.studio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorTreeFoldingTest {
    private fun node(id: String, depth: Int, container: Boolean = false, expanded: Boolean = true) =
        EditorTreeNode(id, id, "task.noop", EditorToolPanel.COMMON, depth, container, expanded)

    @Test fun nestedCollapseHidesOnlyDescendantsAndKeepsSiblingRows() {
        val nodes = listOf(node("parent", 0, true), node("inner", 1, true, false),
            node("leaf", 2), node("sibling", 1), node("root", 0))
        assertEquals(listOf("parent", "inner", "sibling", "root"), legacyVisibleTreeNodes(nodes).map { it.id })
        val collapsed = nodes.map { if (it.id == "parent") it.copy(expanded = false) else it }
        assertEquals(listOf("parent", "root"), legacyVisibleTreeNodes(collapsed).map { it.id })
        val restored = collapsed.map { if (it.id == "parent") it.copy(expanded = true) else it }
        assertEquals(listOf("parent", "inner", "sibling", "root"), legacyVisibleTreeNodes(restored).map { it.id })
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
        val source = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        assertTrue(source.contains("onToggleNode = ::toggleNodeExpansion"))
        assertTrue(source.contains("onToggleExpansion = ::toggleNodeExpansion"))
        val dialog = source.substringAfter("private fun EditorNodeEditDialog(").substringBefore("private fun legacyBreadcrumb(")
        assertTrue(dialog.contains("itemsIndexed(visibleNodes"))
        assertTrue(dialog.contains("editListState.scrollToItem(visibleSelectedIndex)"))
        assertTrue(dialog.contains("onToggleExpansion(node.id)"))
        assertTrue(dialog.contains("onToggleNode?.invoke(node.id)"))
    }
}
