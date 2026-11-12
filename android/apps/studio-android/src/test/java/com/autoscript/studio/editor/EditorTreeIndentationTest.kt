package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorTreeIndentationTest {
    private fun node(id: String, depth: Int = 0, kind: String = "task.noop", slots: List<String> = emptyList(),
        slot: String? = null, expanded: Boolean = true) =
        EditorTreeNode(id, id, kind, depth, slots.isNotEmpty(), expanded, childSlots = slots, childSlot = slot)

    private fun screenshotTree() = listOf(
        node("if", kind = "control.if", slots = listOf("then", "elseIf0", "else")),
        node("down1", 1, slot = "elseIf0"),
        node("folded", 1, "control.if", listOf("then", "else"), "elseIf0", expanded = false),
        node("hidden", 2, slot = "then"),
        node("down2", 1, slot = "elseIf0"),
        node("open", 1, "control.if", listOf("then", "else"), "elseIf0"),
        node("inside", 2, slot = "then"),
        node("root"),
    )

    @Test fun ordinaryAndFoldedContainerSiblingsStayAlignedInsideElseIf() {
        val nodes = screenshotTree()
        val original = nodes.toList()
        val rows = editorTreeDisplayRows(nodes)
        val siblings = rows.filter { it.nodeId in setOf("down1", "folded", "down2", "open") }
        assertEquals(4, siblings.size)
        assertTrue(siblings.all { it.depth == 2 && editorTreeRowIndent(it.depth) == 26 })
        assertEquals(1, rows.single { it.key == "branch:if:elseIf0" }.depth)
        assertEquals(3, rows.single { it.key == "branch:open:then" }.depth)
        assertEquals(4, rows.single { it.nodeId == "inside" }.depth)
        assertEquals(0, rows.last().depth)
        assertFalse(rows.any { it.nodeId == "hidden" })
        rows.filterIsInstance<EditorTreeDisplayRow.Node>().forEach { row ->
            assertSame(nodes.single { it.id == row.nodeId }, row.node)
        }
        assertEquals(original, nodes)
    }

    @Test fun everyCatalogContainerAndSlotKeepMixedChildrenAtTheSameLevel() {
        val containers = BlockCatalog.all.filter { it.childBlocks.isNotEmpty() }
        assertEquals(setOf("control.cycle", "control.repeat", "control.while", "control.if", "flow.call.branch",
            "vision.findimage", "vision.findallimages", "vision.findgray"), containers.map { it.kind }.toSet())
        containers.forEach { parent ->
            parent.childBlocks.forEach { parentSlot ->
                containers.forEach { child ->
                    val nestedSlot = child.childBlocks.first()
                    val nodes = listOf(node("parent", kind = parent.kind, slots = parent.childBlocks),
                        node("before", 1, slot = parentSlot),
                        node("child", 1, child.kind, child.childBlocks, parentSlot),
                        node("nested", 2, slot = nestedSlot), node("after", 1, slot = parentSlot), node("root"))
                    val rows = editorTreeDisplayRows(nodes)
                    val expected = if (parentSlot in editorBranchSlots(parent.kind, parent.childBlocks)) 2 else 1
                    val context = "${parent.kind}/$parentSlot -> ${child.kind}"
                    for (id in listOf("before", "child", "after")) {
                        assertEquals(context, expected, rows.single { it.nodeId == id }.depth)
                    }
                    val nestedStep = if (nestedSlot in editorBranchSlots(child.kind, child.childBlocks)) 2 else 1
                    assertEquals(context, expected + nestedStep, rows.single { it.nodeId == "nested" }.depth)
                    assertEquals(context, 0, rows.single { it.nodeId == "root" }.depth)
                    val groups = editorTreeGrouping(rows)
                    assertFalse(context, groups.rows.single { it.row.nodeId == "after" }.groups.any { it.key == "node:child" })
                }
            }
        }
    }

    @Test fun blockKindAndEmptyBranchesNeverAddAnExtraColumnToSiblingLabels() {
        val nodes = listOf(node("loop", kind = "control.repeat", slots = listOf("body"))) +
            BlockCatalog.all.mapIndexed { index, contract ->
                node("block-$index", 1, contract.kind, contract.childBlocks, "body")
            } + node("root")
        val rows = editorTreeDisplayRows(nodes)
        val siblings = rows.filterIsInstance<EditorTreeDisplayRow.Node>().filter { it.node.id.startsWith("block-") }
        assertEquals(BlockCatalog.all.size, siblings.size)
        assertTrue(siblings.all { it.depth == 1 && editorTreeRowIndent(it.depth) == 14 })
        assertTrue(rows.filterIsInstance<EditorTreeDisplayRow.Branch>().all { it.depth == 2 && it.empty })
        assertEquals(0, rows.last().depth)
    }

    @Test fun foldingAndFocusPreserveRelativeOffsetsAndKeepTheNearestScopeRail() {
        val nodes = screenshotTree()
        val original = editorTreeDisplayRows(nodes).associate { it.key to it.depth }
        val folded = editorTreeDisplayRows(nodes, setOf("branch:open:then"))
        assertFalse(folded.any { it.nodeId == "inside" })
        folded.forEach { assertEquals(original[it.key], it.depth) }
        val focused = editorTreeDisplayRows(editorFocusedTreeNodes(nodes, "open"))
        focused.forEach { assertEquals(requireNotNull(original[it.key]) - 2, it.depth) }
        val grouping = editorTreeGrouping(editorTreeDisplayRows(nodes))
        val focusedKeys = grouping.focusedGroups("node:inside")
        val inside = grouping.rows.single { it.row.nodeId == "inside" }
        assertEquals("branch:open:then", inside.visibleGroups.last { it.key in focusedKeys }.key)
    }

    @Test fun thirtyTwoNestedConditionsDoNotFlattenAtDisplayLevelThirtyTwo() {
        val nodes = (0 until 32).map { depth ->
            node("if-$depth", depth, "control.if", listOf("then", "else"), if (depth == 0) null else "then")
        } + node("leaf", 32, slot = "then") + node("root")
        val rows = editorTreeDisplayRows(nodes)
        val leaf = rows.single { it.nodeId == "leaf" }
        assertEquals(64, leaf.depth)
        for (depth in 0 until 32) {
            assertEquals(depth * 2, rows.single { it.nodeId == "if-$depth" }.depth)
            assertEquals(depth * 2 + 1, rows.single { it.key == "branch:if-$depth:then" }.depth)
        }
        assertEquals(65, (0..64).map(::editorTreeRowIndent).distinct().size)
        val grouping = editorTreeGrouping(rows)
        val leafGroup = grouping.rows.single { it.row.nodeId == "leaf" }
        assertEquals(32, leafGroup.visibleGroups.size)
        assertEquals((32..63).toList(), leafGroup.visibleGroups.map { it.depth })
        assertEquals("branch:if-31:then", leafGroup.visibleGroups.last().key)
        assertTrue(grouping.focusedGroups(leaf.key).contains(leafGroup.visibleGroups.last().key))
        assertTrue(editorTreeContentWidth(260f, leaf.depth) - editorTreeRowIndent(leaf.depth) -
            EDITOR_TREE_CHECK_WIDTH_DP - EDITOR_TREE_EXPAND_WIDTH_DP >= 180f)
        val emptyAtLimit = editorTreeDisplayRows(nodes.dropLast(2) +
            node("empty-if", 32, "control.if", listOf("then", "else"), "then") + node("root"))
        assertEquals(65, emptyAtLimit.single { it.key == "branch:empty-if:then" }.depth)
        assertEquals(EDITOR_TREE_INDENT_STEP_DP, editorTreeRowIndent(65) - editorTreeRowIndent(64))
    }

    @Test fun editTreeAddsOnlyAFixedCheckboxGutterToTheSharedDisclosureAndGroupColumns() {
        for (depth in 0..EDITOR_TREE_MAX_DISPLAY_DEPTH) {
            val normal = editorTreeGuideOffset(depth)
            val edit = editorTreeGuideOffset(depth, EDITOR_TREE_CHECK_WIDTH_DP)
            assertEquals(EDITOR_TREE_CHECK_WIDTH_DP, edit - normal)
            assertEquals(editorTreeRowIndent(depth) + EDITOR_TREE_EXPAND_WIDTH_DP / 2 + EDITOR_TREE_CHECK_WIDTH_DP, edit)
        }
        val source = editorDockTestSources()
        val editRows = source.substringAfter("internal fun EditorNodeEditDialog(").substringBefore("internal fun editorTreeBreadcrumb(")
        assertTrue(editRows.contains("EditorProgramTreeRow(node = node"))
        assertTrue(editRows.contains("extraLeadingDp = EDITOR_TREE_CHECK_WIDTH_DP"))
        assertFalse(editRows.contains("width(EDITOR_TREE_EXPAND_WIDTH_DP.dp)"))
        val row = source.substringAfter("internal fun EditorProgramTreeRow(").substringBefore("internal fun EditorProgramLabel(")
        assertTrue(row.indexOf("if (onCheckSelection != null)") < row.indexOf("padding(start = editorTreeRowIndent(displayDepth)"))
        assertTrue(row.contains("editorTreeGroups(grouping, focusedGroups, leadingDp)"))
    }
}
