package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ConfiguredEditorInsertionTest {
    private val sleep = requireNotNull(BlockCatalog.find("task.sleep"))
    private fun args(ms: Int) = JsonObject().apply { addProperty("milliseconds", ms) }
    private fun editor(): VisualEditorState = VisualEditorState.create("", "root").also {
        it.insertBlock(sleep, args(10)); it.insertBlock(sleep, args(20))
    }
    private fun apply(editor: VisualEditorState, context: EditorPasteRequest,
        position: EditorInsertPosition = EditorInsertPosition.ABOVE, slot: String? = null,
        project: String = "project", flow: String = "main", mutable: Boolean = true,
        capabilities: Set<String> = sleep.requiredCapabilities.toSet()) =
        applyConfiguredEditorInsertion(context, project, flow, editor, sleep, args(30), position, slot, capabilities, mutable)

    @Test fun beforeInsertionIsSelectedAndOneUndoRestoresEntireGesture() {
        val editor = editor(); val original = editor.currentSource
        val anchor = requireNotNull(editor.selectedNodeId)
        assertNull(apply(editor, editorInsertionContext("project", "main", editor)))
        val inserted = requireNotNull(editor.selectedNodeId)
        assertEquals(editor.rows.indexOfFirst { it.nodeId == anchor } - 1, editor.rows.indexOfFirst { it.nodeId == inserted })
        val accepted = editor.currentSource
        assertTrue(editor.undo()); assertEquals(original, editor.currentSource)
        assertTrue(editor.redo()); assertEquals(accepted, editor.currentSource)
    }

    @Test fun differentProjectOrPluginCannotReceiveOldParameters() {
        val editor = editor(); val original = editor.currentSource
        val context = editorInsertionContext("project", "main", editor)
        assertNotNull(apply(editor, context, project = "other"))
        assertNotNull(apply(editor, context, flow = "worker"))
        assertEquals(original, editor.currentSource)
    }

    @Test fun staleDraftAndSelectionRejectWithoutChangingHistoryOrSelection() {
        val editor = editor(); val context = editorInsertionContext("project", "main", editor)
        editor.insertBlock(sleep, args(40))
        val changed = editor.currentSource; val selection = editor.selectedNodeIds
        assertNotNull(apply(editor, context)); assertEquals(changed, editor.currentSource)
        assertEquals(selection, editor.selectedNodeIds)
        assertTrue(editor.undo()); assertEquals(context.source, editor.currentSource)
        editor.selectOnly(editor.rows.first().nodeId)
        assertNotNull(apply(editor, context)); assertEquals(context.source, editor.currentSource)
    }

    @Test fun selectionSetChangesAreStaleEvenWhenPrimaryIsUnchanged() {
        val editor = editor(); val context = editorInsertionContext("project", "main", editor)
        editor.selectNodes(editor.rows.map { it.nodeId })
        assertEquals(context.primary, editor.selectedNodeId)
        assertNotNull(apply(editor, context)); assertEquals(context.source, editor.currentSource)
    }

    @Test fun missingCapabilityOrActiveRuntimeLeavesDraftAndUndoUnchanged() {
        val editor = editor(); val context = editorInsertionContext("project", "main", editor)
        assertNotNull(apply(editor, context, mutable = false))
        assertNotNull(apply(editor, context, capabilities = emptySet()))
        assertEquals(context.source, editor.currentSource); assertEquals(context.selection, editor.selectedNodeIds)
    }

    @Test fun explicitSuccessAndFailureLocationsHaveNoSyntheticNodeIdentity() {
        val editor = editor()
        val call = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("flow.call.branch")),
            JsonObject().apply { addProperty("targetFlowId", "worker"); add("arguments", JsonObject()) }))
        for (slot in listOf("success", "failure")) {
            editor.selectOnly(call)
            assertNull(apply(editor, editorInsertionContext("project", "main", editor), EditorInsertPosition.INSIDE, slot))
            assertEquals(slot, editor.rows.first { it.nodeId == editor.selectedNodeId }.childSlot)
        }
        assertEquals(5, editor.rows.size)
        editor.selectOnly(call)
        val context = editorInsertionContext("project", "main", editor)
        assertNotNull(apply(editor, context, EditorInsertPosition.INSIDE, "missing"))
        assertEquals(context.source, editor.currentSource); assertEquals(context.primary, editor.selectedNodeId)
    }

    @Test fun failedLoopOnlyInsertionAtBottomPreservesMultiSelection() {
        val editor = editor(); editor.selectNodes(editor.rows.map { it.nodeId })
        val context = editorInsertionContext("project", "main", editor)
        val contract = requireNotNull(BlockCatalog.find("control.break"))
        assertNotNull(applyConfiguredEditorInsertion(context, "project", "main", editor, contract, JsonObject(),
            EditorInsertPosition.LIST_BOTTOM, null, contract.requiredCapabilities.toSet(), true))
        assertEquals(context.source, editor.currentSource); assertEquals(context.primary, editor.selectedNodeId)
        assertEquals(context.selection, editor.selectedNodeIds)
    }

    @Test fun positionPickerTicketRejectsEveryStaleDimension() {
        val pending = EditorPendingSnippet("public", {}, "main", "source", "1", setOf("1", "2"))
        assertTrue(pending.matches("main", "source", "1", setOf("1", "2")))
        assertFalse(pending.matches("worker", "source", "1", pending.selection))
        assertFalse(pending.matches("main", "changed", "1", pending.selection))
        assertFalse(pending.matches("main", "source", "2", pending.selection))
        assertFalse(pending.matches("main", "source", "1", setOf("1")))
    }

    @Test fun secondStageBranchChooserAlsoBindsTheFullSelectionSet() {
        val editor = editor(); val selected = editor.selectedNodeId
        val request = EditorDockInsertRequest("project", "main", editor.currentSource, selected, "public", true,
            selection = editor.selectedNodeIds.toSet())
        assertTrue(request.matches("project", "main", editor))
        editor.selectNodes(editor.rows.map { it.nodeId })
        assertEquals(selected, editor.selectedNodeId)
        assertFalse(request.matches("project", "main", editor))
    }
}
