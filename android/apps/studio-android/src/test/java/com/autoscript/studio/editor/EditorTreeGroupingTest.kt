package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorTreeGroupingTest {
    private fun node(id: String, depth: Int = 0, kind: String = "task.noop",
        slots: List<String> = emptyList(), slot: String? = null, expanded: Boolean = true) =
        EditorTreeNode(id, id, kind, depth, slots.isNotEmpty(), expanded, childSlots = slots, childSlot = slot)

    private fun tree() = listOf(
        node("loop", kind = "control.repeat", slots = listOf("body")),
        node("if", 1, "control.if", listOf("then", "else"), "body"),
        node("nested", 2, "control.repeat", listOf("body"), "then"),
        node("leaf", 3, slot = "body"),
        node("otherwise", 2, slot = "else"),
        node("after", 1, slot = "body"),
        node("root"),
    )

    @Test fun nestedScopesEndAtTheirOwnLastChildAndNeverLeakIntoTheNextBranch() {
        val display = editorTreeDisplayRows(tree())
        val grouping = editorTreeGrouping(display)
        fun at(key: String) = grouping.rows.first { it.row.key == key }
        fun group(key: String) = at(key).groups.single { it.key == key }
        assertEquals(at("node:after").index, group("node:loop").last)
        assertEquals(at("node:otherwise").index, group("node:if").last)
        assertEquals(at("node:leaf").index, group("branch:if:then").last)
        assertEquals(at("node:leaf").index, group("node:nested").last)
        assertEquals(at("node:otherwise").index, group("branch:if:else").last)
        assertEquals(listOf("node:loop", "node:if", "branch:if:then", "node:nested"),
            at("node:leaf").groups.map { it.key })
        assertEquals(listOf("node:loop", "node:if", "branch:if:else"), at("node:otherwise").groups.map { it.key })
        assertEquals(listOf("node:loop"), at("node:after").groups.map { it.key })
        assertTrue(at("node:root").groups.isEmpty())
        assertEquals(display, grouping.rows.map { it.row })
    }

    @Test fun emptyBranchRemainsAnAnchorWithoutCreatingAPhantomChildScope() {
        val nodes = listOf(node("if", kind = "control.if", slots = listOf("then", "else")),
            node("child", 1, slot = "then"), node("root"))
        val grouping = editorTreeGrouping(editorTreeDisplayRows(nodes))
        val empty = grouping.rows.first { it.row.key == "branch:if:else" }
        assertEquals(setOf("node:if"), grouping.focusedGroups(empty.row.key))
        assertFalse(empty.groups.any { it.key == empty.row.key })
        assertEquals(empty.index, empty.groups.single().last)
        assertTrue(grouping.rows.last().groups.isEmpty())
    }

    @Test fun foldingClosesVisibleScopesAndRestoringRecreatesTheSameGrouping() {
        val nodes = tree()
        val open = editorTreeGrouping(editorTreeDisplayRows(nodes)).rows
        val collapsed = editorTreeGrouping(editorTreeDisplayRows(nodes.map {
            if (it.id == "if") it.copy(expanded = false) else it
        }))
        assertEquals(listOf("loop", "if", "after", "root"), collapsed.rows.mapNotNull { it.row.nodeId })
        assertEquals(listOf("node:loop"), collapsed.rows[1].groups.map { it.key })
        assertEquals(collapsed.rows[2].index, collapsed.rows[1].groups.single().last)
        assertEquals(open, editorTreeGrouping(editorTreeDisplayRows(nodes)).rows)
    }

    @Test fun focusHighlightsOnlyAncestorsAndTheSelectedOpeningScope() {
        val grouping = editorTreeGrouping(editorTreeDisplayRows(tree()))
        assertEquals(setOf("node:loop", "node:if", "branch:if:then", "node:nested"),
            grouping.focusedGroups("node:leaf"))
        assertEquals(setOf("node:loop", "node:if", "branch:if:else"), grouping.focusedGroups("branch:if:else"))
        assertEquals(setOf("node:loop", "node:if"), grouping.focusedGroups("node:if"))
        for (key in listOf(null, "missing", "node:root")) assertTrue(grouping.focusedGroups(key).isEmpty())
    }

    @Test fun rebasedViewRetainsOriginalExecutableIdentitiesAndDepths() {
        val nodes = tree()
        val original = nodes.toList()
        val focused = editorFocusedTreeNodes(nodes, "if")
        val display = editorTreeDisplayRows(focused)
        val grouping = editorTreeGrouping(display)
        assertEquals(original, nodes)
        assertEquals(listOf("if", "nested", "leaf", "otherwise"), grouping.rows.mapNotNull { it.row.nodeId })
        assertEquals(0, grouping.rows.first().groups.single().depth)
        assertEquals(display, grouping.rows.map { it.row })
        display.filterIsInstance<EditorTreeDisplayRow.Node>().forEach { row ->
            assertSame(focused.first { it.id == row.nodeId }, row.node)
        }
    }

    @Test fun disabledGroupsRemainMutedAndReadOnlyDuplicateKeysRemainDistinct() {
        val disabled = tree().map { if (it.id == "if") it.copy(label = "[随父级禁用] 判断") else it }
        val grouping = editorTreeGrouping(editorTreeDisplayRows(disabled))
        val ifRow = grouping.rows.first { it.row.nodeId == "if" }
        assertTrue(ifRow.groups.last().disabled)
        assertTrue(grouping.rows.first { it.row.key == "branch:if:then" }.groups.last().disabled)
        val duplicates = editorTreeGrouping(editorTreeDisplayRows(listOf(node("same"), node("same", 1), node("next"))))
        assertEquals(3, duplicates.rows.map { it.row.key }.distinct().size)
        assertEquals(setOf(duplicates.rows.first().row.key), duplicates.focusedGroups(duplicates.rows[1].row.key))
        assertTrue(duplicates.rows.last().groups.isEmpty())
    }

    @Test fun drawingBudgetIsBoundedWithoutDiscardingDeepOrMalformedRows() {
        val rows = (-1..40).map { EditorTreeDisplayRow.Node(node("depth-$it", it)) } +
            EditorTreeDisplayRow.Node(node("root"))
        val grouping = editorTreeGrouping(rows)
        val deepest = grouping.rows.first { it.row.nodeId == "depth-40" }
        assertEquals(32, deepest.visibleGroups.size)
        assertEquals((0..31).toList(), deepest.visibleGroups.map { it.depth })
        assertEquals(rows, grouping.rows.map { it.row })
        assertTrue(grouping.rows.last().groups.isEmpty())
        assertTrue(editorTreeGrouping(emptyList()).rows.isEmpty())
    }
}
