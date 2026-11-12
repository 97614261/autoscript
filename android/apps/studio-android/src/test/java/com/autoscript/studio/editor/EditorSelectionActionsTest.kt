package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorSelectionActionsTest {
    private fun node(id: String, block: String = "root", order: String, parent: String? = null,
        kind: String = "task.noop", children: String = ""): String =
        """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$block","parentId":${parent?.let { "\"$it\"" } ?: "null"},"orderKey":"$order","kind":"$kind","nodeVersion":1,${children}"depth":${if (parent == null) 0 else 1},"args":{}}"""

    private fun editor(vararg nodes: String) = VisualEditorState.create(nodes.joinToString("\n", postfix = "\n"), "root")

    private fun forest() = editor(
        node("a", order = "a0"),
        node("owner", order = "b0", kind = "control.repeat", children = "\"childBlocks\":{\"body\":\"body\"},"),
        node("child", "body", "a0", "owner"),
        node("gap", order = "c0"),
        node("b", order = "d0"),
    )

    private fun conditional() = editor(
        node("owner", order = "a0", kind = "control.if",
            children = "\"childBlocks\":{\"then\":\"then-body\",\"elseIf1\":\"elseif-one\",\"elseIf2\":\"elseif-two\",\"else\":\"else-body\"},"),
        node("existing", "then-body", "a0", "owner"),
        node("a", order = "b0"),
        node("b", order = "c0"),
    )

    @Test fun bottomDeleteRefreshesAfterChangingBetweenTwoMultiSelections() {
        val editor = forest()
        val nodes = editor.programDisplayNodes().toEditorTreeNodes()
        var pending: EditorDeleteRequest? = null
        fun action(ids: Set<String>) = EditorEditButton(EditorEditTool.DELETE, true, "批量删除",
            editorDeleteAction(true, editor.currentSource, "main", nodes, ids) { pending = it })
        val previous = action(setOf("a", "b"))
        val current = action(setOf("a", "owner", "b"))
        assertNotEquals("The toolbar must rebind even when enabled state and title stay the same", previous, current)
        current.onClick()
        val request = requireNotNull(pending)
        assertEquals(setOf("a", "owner", "b"), request.selection)
        assertEquals(4, request.affected)
        assertTrue(editor.selectNodes(request.selection))
        val source = editor.currentSource
        assertTrue(request.matches(source, "main", editor.selectedNodeIds))
        assertTrue(editor.deleteSelection())
        assertEquals(listOf("gap"), editor.rows.map { it.nodeId })
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
        assertFalse(editor.canUndo)
    }

    @Test fun deleteIgnoresFoldingButRejectsChangedContentFlowOrSelection() {
        val editor = forest()
        val selected = setOf("owner", "child", "b")
        val nodes = editor.programDisplayNodes().toEditorTreeNodes()
        val request = requireNotNull(editorDeleteRequest(editor.currentSource, "main", nodes, selected))
        val folded = editor.programDisplayNodes().toEditorTreeNodes(listOf("owner"))
        assertNotEquals(nodes, folded)
        assertEquals(request, editorDeleteRequest(editor.currentSource, "main", folded, selected))
        assertEquals(3, request.affected)
        assertTrue(request.matches(editor.currentSource, "main", selected.reversed().toSet()))
        assertFalse(request.matches(editor.currentSource, "other", selected))
        assertFalse(request.matches(editor.currentSource, "main", setOf("b")))
        assertFalse(request.matches(editor.currentSource + "\n", "main", selected))
        assertNull(editorDeleteRequest(editor.currentSource, "main", nodes, setOf("missing")))
        assertNull(editorDeleteRequest(editor.currentSource, "main", nodes, emptySet()))
    }

    @Test fun cutIncludesAllSelectedRootsAndDescendantsWithSingleUndoAndRedo() {
        val editor = forest()
        val source = editor.currentSource
        assertTrue(editor.selectNodes(setOf("a", "owner", "child", "b")))
        val result = executeEditorCommand(editor, EditorProgramCommand.CUT) as EditorCommandResult.Clipboard
        assertTrue(result.cut)
        assertTrue(result.changed)
        val copied = requireNotNull(result.nodes)
        assertEquals(listOf("a", "owner", "b"), copied.map { it.rootNodeId })
        assertEquals(4, copied.sumOf { it.nodes.size })
        assertEquals(listOf("gap"), editor.rows.map { it.nodeId })
        val after = editor.currentSource
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
        assertFalse(editor.canUndo)
        assertTrue(editor.redo())
        assertEquals(after, editor.currentSource)
        editor.selectOnly("gap")
        assertTrue(editor.pasteSelection(copied))
        assertEquals(5, editor.rows.size)
        assertEquals(3, editor.selectedNodeIds.size)
        assertEquals(1, editor.rows.count { it.depth == 1 })
        assertEquals(5, editor.rows.map { it.nodeId }.distinct().size)
    }

    @Test fun cutWithoutAnEditableSelectionDoesNotProduceAReplacementClipboard() {
        val editor = forest()
        val source = editor.currentSource
        editor.selectOnly(null)
        val result = executeEditorCommand(editor, EditorProgramCommand.CUT) as EditorCommandResult.Clipboard
        assertTrue(result.cut)
        assertFalse(result.changed)
        assertNull(result.nodes)
        assertEquals(source, editor.currentSource)
        assertFalse(editor.canUndo)
    }

    @Test fun forestPastesIntoEveryConditionBranchInOrderAndCanUndoAsOneOperation() {
        for (slot in listOf("then", "elseIf1", "elseIf2", "else")) {
            val editor = conditional()
            assertTrue(editor.selectNodes(setOf("a", "b")))
            val clipboard = editor.copySelection()
            editor.selectOnly("owner")
            val branch = EditorBranchSelection("owner", slot, editor.currentSource)
            val request = EditorPasteRequest("p", "main", editor.currentSource, "owner", editor.selectedNodeIds, branch)
            val destination = EditorPasteDestination(childSlot = slot)
            assertTrue(request.matches("p", "main", editor, branch))
            assertTrue(request.accepts(destination))
            val source = editor.currentSource
            assertTrue(editor.pasteSelection(clipboard, slot = destination.childSlot))
            val inserted = editor.rows.filter { it.nodeId in editor.selectedNodeIds }
            assertEquals(2, inserted.size)
            assertTrue(inserted.all { it.childSlot == slot && it.depth == 1 })
            if (slot == "then") assertEquals("existing", editor.rows.first { it.childSlot == slot }.nodeId)
            assertEquals(listOf("a", "b"), clipboard.map { it.rootNodeId })
            assertTrue(editor.undo())
            assertEquals(source, editor.currentSource)
            assertFalse(editor.canUndo)
        }
    }

    @Test fun branchPasteRejectsSwitchingBranchesEvenWhenTheSameOwnerStaysSelected() {
        val editor = conditional()
        editor.selectOnly("owner")
        val branch = EditorBranchSelection("owner", "then", editor.currentSource)
        val request = EditorPasteRequest("p", "main", editor.currentSource, "owner", editor.selectedNodeIds, branch)
        assertTrue(request.matches("p", "main", editor, branch))
        assertFalse(request.matches("p", "main", editor, branch.copy(slot = "else")))
        assertFalse(request.matches("p", "main", editor))
        assertFalse(request.accepts(EditorPasteDestination()))
        assertFalse(request.accepts(EditorPasteDestination(childSlot = "else")))
        assertFalse(request.accepts(EditorPasteDestination(before = true, childSlot = "then")))
        val oldLineRequest = request.copy(branch = null)
        assertFalse(oldLineRequest.matches("p", "main", editor, branch))
        editor.selectOnly("a")
        assertFalse(request.matches("p", "main", editor, branch))
    }

    @Test fun directPasteAlsoSupportsRecognitionSuccessAndFailureBranches() {
        for (slot in listOf("success", "failure")) {
            val editor = editor(
                node("owner", order = "a0", kind = "vision.findimage",
                    children = "\"childBlocks\":{\"success\":\"success-body\",\"failure\":\"failure-body\"},"),
                node("a", order = "b0"),
            )
            editor.selectOnly("a")
            val clipboard = editor.copySelection()
            editor.selectOnly("owner")
            val branch = EditorBranchSelection("owner", slot, editor.currentSource)
            val request = EditorPasteRequest("p", "main", editor.currentSource, "owner", editor.selectedNodeIds, branch)
            assertTrue(request.matches("p", "main", editor, branch))
            assertTrue(editor.pasteSelection(clipboard, slot))
            assertEquals(slot, editor.rows.single { it.nodeId == editor.selectedNodeId }.childSlot)
        }
    }
}
