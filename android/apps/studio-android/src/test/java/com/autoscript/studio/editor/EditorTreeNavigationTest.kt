package com.autoscript.studio

import com.autoscript.studio.compiler.NodeDisplaySettings
import org.junit.Assert.*
import org.junit.Test

class EditorTreeNavigationTest {
    private fun node(id: String, depth: Int = 0, kind: String = "task.noop", slot: String? = null,
        slots: List<String> = emptyList(), expanded: Boolean = true) = EditorTreeNode(
        id, id, kind, depth, slots.isNotEmpty(), expanded, childSlots = slots, childSlot = slot)

    @Test fun conditionAndImageHeadingsAreOrderedAnchorsNotNodes() {
        val tree = listOf(node("if", kind = "control.if", slots = listOf("else", "elseIf10", "then", "elseIf2")),
            node("otherwise", 1, slot = "else"), node("two", 1, slot = "elseIf2"),
            node("first", 1, slot = "then"), node("ten", 1, slot = "elseIf10"))
        val rows = editorTreeDisplayRows(tree)
        assertEquals(listOf("then", "elseIf2", "elseIf10", "else"),
            rows.filterIsInstance<EditorTreeDisplayRow.Branch>().map { it.slot })
        assertEquals(listOf("if", "first", "two", "ten", "otherwise"), rows.mapNotNull { it.nodeId })
        assertEquals(listOf("success", "failure"), editorBranchSlots("vision.findallimages", listOf("failure", "success")))
        assertTrue(editorBranchSlots("control.repeat", listOf("body")).isEmpty())
    }

    @Test fun descendantCountsIgnoreFoldingAndExcludeHeadings() {
        val tree = listOf(node("parent", slots = listOf("body"), expanded = false), node("nested", 1),
            node("leaf", 2), node("sibling", 1), node("root"))
        assertEquals(mapOf("parent" to 3, "nested" to 1, "leaf" to 0, "sibling" to 0, "root" to 0), editorDescendantCounts(tree))
        assertEquals(listOf("parent", "root"), editorTreeDisplayRows(tree).mapNotNull { it.nodeId })
    }

    @Test fun rangesAreSymmetricBoundedAndUseOnlyVisibleNodes() {
        val visible = editorTreeDisplayRows(listOf(node("if", kind = "control.if", slots = listOf("then", "else")),
            node("leaf", 1, slot = "then"), node("root"))).mapNotNull { it.nodeId }
        assertEquals(linkedSetOf("if", "leaf", "root"), editorRangeSelection(visible, "root", "if"))
        assertEquals(setOf("leaf"), editorRangeSelection(visible, "leaf", "leaf"))
        assertNull(editorRangeSelection(visible, "branch:if:then", "root"))
        val many = (0..256).map(Int::toString)
        assertEquals(256, editorRangeSelection(many, "0", "255")?.size)
        assertNull(editorRangeSelection(many, "0", "256"))
        val editor = VisualEditorState.create("", "root") { "id" }
        editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")), com.google.gson.JsonObject())
        val previous = editor.selectedNodeIds.toSet()
        assertFalse(editor.selectNodes(many))
        assertEquals(previous, editor.selectedNodeIds)
    }

    @Test fun afterPreviewIsAfterWholeContainerNotItsFirstChild() {
        val tree = listOf(node("loop", kind = "control.repeat", slots = listOf("body")),
            node("child", 1, slot = "body"), node("nested", 2), node("next"))
        val preview = editorInsertionPreview(tree, "loop", EditorInsertPosition.BELOW)
        assertEquals(listOf("child", "nested", "加入本行及其子树之后", "next"), preview.lines.map { it.label })
        assertEquals(0, preview.lines.single { it.marker }.depth)
        assertEquals("loop", editorInsertionPreview(tree, "loop", EditorInsertPosition.ABOVE).lines[1].label)
        assertEquals(1, editorInsertionPreview(tree, "loop", EditorInsertPosition.INSIDE).lines.single { it.marker }.depth)
    }

    @Test fun childPreviewShowsEmptySlotAndNeverInventsFirstBranch() {
        val tree = listOf(node("call", kind = "flow.call.branch", slots = listOf("success", "failure")),
            node("good", 1, slot = "success"), node("next"))
        assertTrue(editorInsertionPreview(tree, "call", EditorInsertPosition.INSIDE).lines.isEmpty())
        val success = editorInsertionPreview(tree, "call", EditorInsertPosition.INSIDE, "success")
        val marker = success.lines.indexOfFirst { it.marker }
        assertEquals("good", success.lines[marker - 1].label)
        assertEquals("失败分支", success.lines[marker + 1].label)
        val failure = editorInsertionPreview(tree, "call", EditorInsertPosition.INSIDE, "failure")
        val failedMarker = failure.lines.indexOfFirst { it.marker }
        assertEquals("失败分支", failure.lines[failedMarker - 1].label)
        assertEquals("next", failure.lines[failedMarker + 1].label)
        assertTrue(editorInsertionPreview(tree, "call", EditorInsertPosition.INSIDE, "deleted").lines.isEmpty())
    }

    @Test fun missingAnchorAndEmptyPluginRemainHonest() {
        assertTrue(editorInsertionPreview(emptyList(), "missing", EditorInsertPosition.BELOW).lines.isEmpty())
        val empty = editorInsertionPreview(emptyList(), null, EditorInsertPosition.LIST_BOTTOM)
        assertEquals(1, empty.lines.size)
        assertTrue(empty.lines.single().marker)
        assertEquals(0, empty.lines.single().depth)
    }

    @Test fun expandedSummaryPreservesLiteralAliasAndHiddenParameters() {
        val alias = "[已禁用] [分支] · 字面别名"
        val hidden = NodeDisplaySettings(alias = alias, showParameters = false)
        val full = displayProgramRowText("点击 · 完整参数", hidden, "满足", false, false, branchHasHeading = true)
        assertEquals(alias, full.title)
        assertEquals("", full.details)
        assertFalse(full.disabled)
        assertNull(compactProgramBranchLabel(full.branch, full.branchHasHeading))
        val tree = listOf(EditorProgramNode("id", alias, "input.tap", 0, fullDisplayRow = full)).toEditorTreeNodes()
        assertEquals(full, tree.single().fullDisplayRow)
    }

    @Test fun userScrollDetachesFollowingAndAncestorExpansion() {
        val source = editorDockTestSources()
        assertTrue(source.contains("source == NestedScrollSource.UserInput"))
        assertTrue(source.contains("if (executionAvailable && !followExecution) return@LaunchedEffect"))
        assertTrue(source.contains("programCurrentNodeId.takeIf { followExecution }"))
        assertTrue(source.contains("回到运行行"))
        assertTrue(source.contains("expandedSummaryId == node.id"))
    }

    @Test fun newBranchAnchorsInsertIntoExactSlotAndBecomeStale() {
        for ((kind, slot) in listOf("control.if" to "else", "vision.findallimages" to "failure")) {
            var next = 0
            val editor = VisualEditorState.create("", "root") { "new-${++next}" }
            val owner = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find(kind)), com.google.gson.JsonObject()))
            val source = editor.currentSource
            val branch = EditorBranchSelection(owner, slot, source)
            assertTrue(branch.matches(editor))
            val leaf = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")),
                com.google.gson.JsonObject(), intoChildBlockName = slot))
            assertEquals(slot, editor.rows.single { it.nodeId == leaf }.childSlot)
            val projection = editor.programDisplayNodes()
            assertTrue(requireNotNull(projection.single { it.nodeId == leaf }.displayRow).branchHasHeading)
            assertFalse(branch.matches(editor))
            assertTrue(editor.undo())
            assertEquals(source, editor.currentSource)
        }
    }
}
