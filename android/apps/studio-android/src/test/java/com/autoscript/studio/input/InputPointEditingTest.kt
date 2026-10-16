package com.autoscript.studio

import com.autoscript.runtime.api.InputPointAction
import com.autoscript.runtime.api.InputPointPickReply
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class InputPointEditingTest {
    private fun reply(action: InputPointAction) = InputPointPickReply(7L, 0, action.wire, 1440, 2560, 720, 1280, 1000, 2000, 700)
    private fun batch() = prepareInputBlocks(inputPointSnippet(reply(InputPointAction.LONG_PRESS), 720, 1280, "stretch"), setOf("input.basic", "core.task"))

    @Test fun allSevenActionsGenerateTypedBlocksWithSharedPhysicalCoordinateMapping() {
        InputPointAction.entries.forEach { action ->
            val snippet = inputPointSnippet(reply(action), 720, 1280, "stretch")
            assertNull(snippet.rejection)
            assertTrue(snippet.flowBlocks.isNotEmpty())
            if (action != InputPointAction.UP) {
                val first = snippet.flowBlocks.first().arguments
                assertEquals(360, first[if (action == InputPointAction.SWIPE) "x1" else "x"])
                assertEquals(640, first[if (action == InputPointAction.SWIPE) "y1" else "y"])
            }
        }
        assertEquals(listOf("input.pointerup"), inputPointSnippet(reply(InputPointAction.UP), 720, 1280, "stretch").flowBlocks.map { it.kind })
    }

    @Test fun preparationRequiresRealCapabilitiesAndRejectsUnrelatedNodes() {
        val snippet = inputPointSnippet(reply(InputPointAction.LONG_PRESS), 720, 1280, "stretch")
        assertThrows(IllegalArgumentException::class.java) { prepareInputBlocks(snippet, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { prepareInputBlocks(snippet.copy(flowBlocks = listOf(ImageToolCodeGen.FlowBlockInsertion("control.goto"))), setOf("input.basic", "core.task")) }
    }

    @Test fun batchPositionsKeepGestureOrderAndOneUndo() {
        listOf(EditorInsertPosition.ABOVE, EditorInsertPosition.BELOW, EditorInsertPosition.LIST_BOTTOM).forEach { position ->
            var id = 0
            val editor = VisualEditorState.create("", "root") { "test-${++id}" }
            val first = editor.insertNoop()!!
            val last = editor.insertNoop()!!
            val before = editor.currentSource
            assertTrue(editor.insertBlocks(batch(), position, first))
            val ids = editor.rows.map { it.nodeId }
            val kinds = editor.rows.map { it.kind }
            val start = when (position) { EditorInsertPosition.ABOVE -> 0; EditorInsertPosition.BELOW -> 1; else -> 2 }
            assertEquals(listOf("input.pointerdown", "task.sleep", "input.pointerup"), kinds.subList(start, start + 3))
            assertTrue(ids.indexOf(first) < ids.indexOf(last))
            assertTrue(editor.undo())
            assertEquals(before, editor.currentSource)
            assertTrue(editor.redo())
            assertEquals(kinds, editor.rows.map { it.kind })
        }
    }

    @Test fun insideTargetsChosenChildAndNeverLeavesPartialGesture() {
        var id = 0
        val editor = VisualEditorState.create("", "root") { "child-${++id}" }
        val container = editor.insertBlock(requireNotNull(BlockCatalog.find("control.if")), JsonObject())!!
        val slot = editor.childBlockNames(container).last()
        val before = editor.currentSource
        assertFalse(editor.insertBlocks(batch(), EditorInsertPosition.INSIDE, container, "missing"))
        assertEquals(before, editor.currentSource)
        assertTrue(editor.insertBlocks(batch(), EditorInsertPosition.INSIDE, container, slot))
        assertEquals(listOf(slot, slot, slot), editor.rows.drop(1).map { it.childSlot })
        assertTrue(editor.undo())
        assertEquals(before, editor.currentSource)
    }

    @Test fun draftCannotWriteIntoChangedPluginOrChangedSource() {
        val editor = VisualEditorState.create("", "root")
        val draft = InputPickTarget("project", "main", editor.currentSource, null).draft(ImageToolCodeGen.pointerUp())
        assertTrue(draft.matches("project", "main", editor))
        assertFalse(draft.matches("project", "other", editor))
        assertFalse(draft.matches("other", "main", editor))
        editor.insertNoop()
        assertFalse(draft.matches("project", "main", editor))
    }

    @Test fun failureAfterPointerDownRollsBackSourceSelectionAndUndo() {
        var id = 0
        val editor = VisualEditorState.create("", "root") { "failure-${++id}" }
        val anchor = editor.insertNoop()
        val before = editor.currentSource
        val invalidTail = batch().take(1) + (requireNotNull(BlockCatalog.find("control.loopmetric")) to JsonObject())
        assertFalse(editor.insertBlocks(invalidTail))
        assertEquals(before, editor.currentSource)
        assertEquals(anchor, editor.selectedNodeId)
        assertTrue(editor.undo())
        assertEquals("", editor.currentSource)
    }

    @Test fun unexpectedInsertionExceptionCannotLeaveOrphanedDown() {
        var id = 0
        val editor = VisualEditorState.create("", "root") { if (++id > 1) error("id generation failed") else "first" }
        assertFalse(editor.insertBlocks(batch()))
        assertEquals("", editor.currentSource)
        assertNull(editor.selectedNodeId)
        assertFalse(editor.canUndo)
    }
}
