package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ImageBlockInsertionTest {
    private val getColor = requireNotNull(BlockCatalog.find("vision.getcolor"))
    private fun arguments() = JsonObject().apply {
        addProperty("frameVariable", "frame"); addProperty("resultVariable", "color")
        add("point", JsonObject().apply { addProperty("x", 10); addProperty("y", 20) })
    }
    private fun blocks() = recognitionImageEntryBlocks(getColor, arguments())
    private fun capabilities() = blocks().flatMap { it.first.requiredCapabilities }.toSet()
    private fun request(editor: VisualEditorState) = requireNotNull(ImageBlockInsertionRequest.create(
        "project", "main", editor, blocks(), ImageInsertionOrigin.CONFIGURATION))

    @Test fun beforeAfterAndBottomPreserveCaptureMatchReleaseOrderAndSingleUndo() {
        for (position in listOf(EditorInsertPosition.ABOVE, EditorInsertPosition.BELOW, EditorInsertPosition.LIST_BOTTOM)) {
            val editor = VisualEditorState.create("", "root")
            val first = requireNotNull(editor.insertNoop())
            editor.insertNoop(); editor.selectOnly(first)
            val original = editor.currentSource
            assertTrue(position.name, request(editor).apply(editor, "project", "main", capabilities(), position))
            val kinds = editor.rows.map { it.kind }
            val sequence = listOf("screen.capture", "vision.getcolor", "screen.release")
            val expected = when (position) {
                EditorInsertPosition.ABOVE -> sequence + listOf("task.noop", "task.noop")
                EditorInsertPosition.BELOW -> listOf("task.noop") + sequence + "task.noop"
                else -> listOf("task.noop", "task.noop") + sequence
            }
            assertEquals(expected, kinds)
            assertTrue(editor.undo()); assertEquals(original, editor.currentSource)
        }
    }

    @Test fun insideUsesRealBranchAndDoesNotMoveAnyBatchPartOutside() {
        val editor = VisualEditorState.create("", "root")
        val container = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.if")), JsonObject()))
        val original = editor.currentSource
        val slots = editor.childBlockNames(container)
        assertTrue(slots.containsAll(listOf("then", "else")))
        assertTrue(request(editor).apply(editor, "project", "main", capabilities(), EditorInsertPosition.INSIDE, "else"))
        assertEquals(listOf("screen.capture", "vision.getcolor", "screen.release"), editor.rows.drop(1).map { it.kind })
        assertTrue(editor.rows.drop(1).all { it.depth == 1 && it.childSlot == "else" })
        assertTrue(editor.undo()); assertEquals(original, editor.currentSource)
        assertFalse(request(editor).apply(editor, "project", "main", capabilities(), EditorInsertPosition.INSIDE, "missing"))
        assertEquals(original, editor.currentSource)
    }

    @Test fun staleContextMissingCapabilitiesAndReplaceCannotMutateTheDraft() {
        val editor = VisualEditorState.create("", "root")
        val id = requireNotNull(editor.insertNoop())
        val pending = request(editor)
        val original = editor.currentSource
        assertFalse(pending.apply(editor, "other", "main", capabilities(), EditorInsertPosition.BELOW))
        assertFalse(pending.apply(editor, "project", "other", capabilities(), EditorInsertPosition.BELOW))
        assertFalse(pending.apply(editor, "project", "main", emptySet(), EditorInsertPosition.BELOW))
        assertFalse(pending.apply(editor, "project", "main", capabilities(), EditorInsertPosition.REPLACE))
        editor.selectOnly(null)
        assertFalse(pending.apply(editor, "project", "main", capabilities(), EditorInsertPosition.LIST_BOTTOM))
        editor.selectOnly(id); assertEquals(original, editor.currentSource)
        editor.insertNoop()
        val changed = editor.currentSource
        assertFalse(pending.apply(editor, "project", "main", capabilities(), EditorInsertPosition.BELOW))
        assertEquals(changed, editor.currentSource)
    }

    @Test fun noSelectionAppendsAndConfigurationsAreFrozenWithoutImplicitInsertion() {
        val editor = VisualEditorState.create("", "root")
        val args = arguments()
        val originalBlocks = recognitionImageEntryBlocks(getColor, args)
        val pending = requireNotNull(ImageBlockInsertionRequest.create("project", "main", editor,
            originalBlocks, ImageInsertionOrigin.PREVIEW))
        args.addProperty("frameVariable", "changed")
        originalBlocks[1].second.addProperty("frameVariable", "changedAgain")
        assertEquals("frame", pending.blocks[1].second["frameVariable"].asString)
        assertEquals("", editor.currentSource)
        assertFalse(pending.apply(editor, "project", "main", capabilities(), EditorInsertPosition.ABOVE))
        assertTrue(pending.apply(editor, "project", "main", capabilities(), EditorInsertPosition.LIST_BOTTOM))
        assertFalse(pending.apply(editor, "project", "main", capabilities(), EditorInsertPosition.LIST_BOTTOM))
        assertNull(ImageBlockInsertionRequest.create("project", "main", editor, emptyList(), ImageInsertionOrigin.PREVIEW))
        assertNull(ImageBlockInsertionRequest.create("project", "main", editor,
            List(257) { getColor to args }, ImageInsertionOrigin.PREVIEW))
    }
}
