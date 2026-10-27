package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EditorBranchPresentationTest {
    @Test fun selectedBranchIsAnExactInsertionAnchorAndBecomesStaleAfterEditingOrSelectingANode() {
        val editor = editor()
        val owner = call(editor)
        val before = editor.currentSource
        val success = EditorBranchSelection(owner, "success", before)
        val failure = success.copy(slot = "failure")
        assertTrue(success.matches(editor))
        assertTrue(failure.matches(editor))
        assertFalse(success.copy(slot = "body").matches(editor))
        assertEquals(before, editor.currentSource)
        val node = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")), JsonObject(),
            intoChildBlockName = failure.slot))
        assertEquals("failure", editor.rows.single { it.nodeId == node }.childSlot)
        assertFalse(failure.matches(editor))
        editor.selectedNodeId = owner
        assertFalse(failure.matches(editor))
        assertTrue(editor.undo())
        assertEquals(before, editor.currentSource)
    }

    @Test fun branchInsertionPreferencesAreFrozenForInputAndImageRequests() {
        val editor = editor()
        val owner = call(editor)
        val input = InputPickTarget("p", "main", editor.currentSource, owner, "failure")
            .draft(ImageToolCodeGen.pointerUp())
        assertEquals("failure", input.preferredChildSlot)
        val image = requireNotNull(ImageBlockInsertionRequest.create("p", "main", editor,
            listOf(requireNotNull(BlockCatalog.find("task.noop")) to JsonObject()), ImageInsertionOrigin.CONFIGURATION,
            preferredChildSlot = "success"))
        assertEquals("success", image.preferredChildSlot)
        assertEquals(owner, image.context.primary)
        assertTrue(image.apply(editor, "p", "main", emptySet(), EditorInsertPosition.INSIDE, image.preferredChildSlot))
        assertEquals("success", editor.rows.single { it.kind == "task.noop" }.childSlot)
    }

    private var nextId = 0
    private fun editor() = VisualEditorState.create("", "root") { "branch-${++nextId}" }
    private fun call(editor: VisualEditorState): String = requireNotNull(editor.insertBlock(
        requireNotNull(BlockCatalog.find("flow.call.branch")), JsonObject().apply {
            addProperty("targetFlowId", "worker")
            add("arguments", JsonObject())
        }))

    private fun tree(editor: VisualEditorState, collapsed: Set<String> = emptySet()): List<EditorTreeNode> =
        editor.programDisplayNodes().map { node -> EditorTreeNode(
            node.nodeId, node.label, node.kind, node.depth, node.childSlots.isNotEmpty(),
            node.nodeId !in collapsed, node.displayRow, node.childSlots, node.childSlot,
        ) }

    @Test fun emptyBranchHeadingsExistWithoutAddingExecutableOrSelectableNodes() {
        val editor = editor()
        val owner = call(editor)
        val source = editor.currentSource
        val nodes = tree(editor)
        val display = editorTreeDisplayRows(nodes)
        val headings = display.filterIsInstance<EditorTreeDisplayRow.Branch>()
        assertEquals(listOf("success", "failure"), headings.map { it.slot })
        assertEquals(listOf("成功分支", "失败分支"), headings.map { childBlockLabel(it.slot) })
        assertTrue(headings.all { it.empty && it.nodeId == null && it.ownerId == owner })
        assertEquals(listOf(owner), display.mapNotNull { it.nodeId })
        assertEquals(display.size, display.map { it.key }.distinct().size)
        assertTrue(editorTreeHasChildren(nodes, 0))
        assertEquals(1, editor.rows.size)
        assertEquals(source, editor.currentSource)
    }

    @Test fun eachBranchOwnsItsActualChildrenAndNestedSubtreeInPresentationOrder() {
        val editor = editor()
        val owner = call(editor)
        val leaf = requireNotNull(BlockCatalog.find("task.noop"))
        editor.selectedNodeId = owner
        val failed = requireNotNull(editor.insertBlock(leaf, JsonObject(), intoChildBlockName = "failure"))
        editor.selectedNodeId = owner
        val nested = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.repeat")),
            JsonObject().apply { addProperty("times", 1) }, intoChildBlockName = "success"))
        editor.selectedNodeId = nested
        val nestedLeaf = requireNotNull(editor.insertBlock(leaf, JsonObject(), intoChildBlockName = "body"))
        editor.selectedNodeId = owner
        val source = editor.currentSource
        val rows = editorTreeDisplayRows(tree(editor))
        assertEquals(listOf(owner, nested, nestedLeaf, failed), rows.mapNotNull { it.nodeId })
        val successHeader = rows.indexOfFirst { it is EditorTreeDisplayRow.Branch && it.slot == "success" }
        val failureHeader = rows.indexOfFirst { it is EditorTreeDisplayRow.Branch && it.slot == "failure" }
        assertTrue(successHeader < rows.indexOfFirst { it.nodeId == nested })
        assertTrue(failureHeader > rows.indexOfFirst { it.nodeId == nestedLeaf })
        assertTrue(rows.filterIsInstance<EditorTreeDisplayRow.Branch>().none { it.empty })
        assertEquals("failure", editor.programDisplayNodes().first { it.nodeId == failed }.childSlot)
        assertEquals(source, editor.currentSource)
    }

    @Test fun foldAndRestoreIncludeEmptyHeadingsAndDoNotLoseRunOrEditIdentities() {
        val editor = editor()
        val outer = call(editor)
        editor.selectedNodeId = outer
        val inner = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("flow.call.branch")),
            JsonObject().apply { addProperty("targetFlowId", "inner"); add("arguments", JsonObject()) },
            intoChildBlockName = "success"))
        val open = editorTreeDisplayRows(tree(editor))
        assertEquals(4, open.filterIsInstance<EditorTreeDisplayRow.Branch>().size)
        val collapsedInner = editorTreeDisplayRows(tree(editor, setOf(inner)))
        assertEquals(2, collapsedInner.filterIsInstance<EditorTreeDisplayRow.Branch>().size)
        assertEquals(listOf(outer, inner), collapsedInner.mapNotNull { it.nodeId })
        assertEquals(listOf(outer), editorTreeDisplayRows(tree(editor, setOf(outer))).mapNotNull { it.nodeId })
        assertEquals(1, editorTreeDisplayRows(tree(editor, setOf(outer))).size)
        assertEquals(open, editorTreeDisplayRows(tree(editor)))
    }

    @Test fun headingsFollowInheritedDisabledPresentationWithoutChangingStatus() {
        val node = EditorTreeNode("disabled", "[已禁用] 调用插件", "flow.call.branch", container = true,
            childSlots = listOf("failure", "success"))
        assertTrue(editorTreeDisplayRows(listOf(node)).filterIsInstance<EditorTreeDisplayRow.Branch>().all { it.disabled })
    }

    @Test fun malformedReadOnlyDuplicateIdentitiesCannotCreateCyclicViewOwnership() {
        val nodes = listOf(EditorTreeNode("duplicate", "first", "task.noop"),
            EditorTreeNode("duplicate", "second", "task.noop", depth = 1))
        val rows = editorTreeDisplayRows(nodes)
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.distinct().size)
    }

    @Test fun branchChooserHasBothDestinationsAndRejectsStalePluginDraftOrSelection() {
        val editor = editor()
        val owner = call(editor)
        val request = EditorDockInsertRequest("project", "main", editor.currentSource, owner, "hint", true)
        val slots = editor.childBlockNames(owner)
        assertEquals(setOf("success", "failure"), request.options(slots).mapNotNull { it.first }.toSet())
        assertFalse(request.options(slots).any { it.first == null })
        assertTrue(request.copy(insideOnly = false).options(slots).any { it.first == null })
        assertTrue(request.matches("project", "main", editor))
        assertFalse(request.matches("other", "main", editor))
        assertFalse(request.matches("project", "other", editor))
        editor.selectedNodeId = null
        assertFalse(request.matches("project", "main", editor))
        editor.selectedNodeId = owner
        editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")), JsonObject(), intoChildBlockName = "failure")
        editor.selectedNodeId = owner
        assertFalse(request.matches("project", "main", editor))
        assertTrue(editor.undo())
        assertEquals(setOf("success", "failure"), editor.childBlockNames(owner).toSet())
    }
}
