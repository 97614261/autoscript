package com.autoscript.studio

import com.autoscript.studio.compiler.NodeDisplaySettings
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EditorProjectionCacheTest {
    private fun editor(): VisualEditorState {
        var next = 0
        return VisualEditorState.create("", "root") { "id-${++next}" }
    }

    @Test fun selectionAndSaveMarkersReuseExactProjectionInstances() {
        val editor = editor()
        val first = requireNotNull(editor.insertNoop())
        val second = requireNotNull(editor.insertNoop())
        val projection = editor.programDisplayNodes(mapOf("main" to "Main"))
        val rows = editor.rows
        editor.selectOnly(first)
        editor.toggleSelection(second)
        assertTrue(editor.selectNodes(listOf(second)))
        editor.markSaved(editor.currentSource)
        assertSame(rows, editor.rows)
        assertSame(projection, editor.programDisplayNodes(mapOf("main" to "Main")))
        assertFalse(editor.isDirty)
    }

    @Test fun editsUndoRedoAndRejectedEditsCannotLeakMutableDocuments() {
        val editor = editor()
        val id = requireNotNull(editor.insertNoop())
        val originalSource = editor.currentSource
        val original = editor.programDisplayNodes()
        assertFalse(editor.updateNodeDisplay("missing", NodeDisplaySettings("ignored")))
        assertSame(original, editor.programDisplayNodes())
        assertTrue(editor.updateNodeDisplay(id, NodeDisplaySettings("Changed")))
        val changed = editor.programDisplayNodes()
        assertNotSame(original, changed)
        assertEquals("Changed", changed.single().displayRow?.title)
        assertFalse(original.single().label.contains("Changed"))
        assertTrue(editor.undo())
        assertEquals(originalSource, editor.currentSource)
        assertEquals(original, editor.programDisplayNodes())
        assertTrue(editor.redo())
        assertEquals(changed, editor.programDisplayNodes())
        val arguments = requireNotNull(editor.nodeArguments(id))
        arguments.addProperty("message", "external mutation")
        assertFalse(editor.currentSource.contains("external mutation"))
    }

    @Test fun pluginRenamesInvalidateDisplayButNotRawRowsAndNamesAreCopied() {
        val editor = editor()
        editor.insertBlock(requireNotNull(BlockCatalog.find("flow.call")), JsonObject().apply { addProperty("targetFlowId", "worker") })
        val names = mutableMapOf("worker" to "First")
        val first = editor.programDisplayNodes(names)
        val rows = editor.rows
        assertTrue(first.single().label.contains("First"))
        names["worker"] = "Second"
        val second = editor.programDisplayNodes(names)
        assertNotSame(first, second)
        assertTrue(second.single().label.contains("Second"))
        assertTrue(first.single().label.contains("First"))
        assertSame(rows, editor.rows)
        assertSame(second, editor.programDisplayNodes(mapOf("worker" to "Second")))
        assertFalse(editor.programDisplayNodes().single().label.contains("Second"))
    }

    @Test fun cachedCollectionsRejectMutationAndKeepSeparateEditorsIsolated() {
        val editor = editor()
        editor.insertNoop()
        val projection = editor.programDisplayNodes()
        try {
            (projection as MutableList<*>).clear()
            fail("display projection must be immutable")
        } catch (_: UnsupportedOperationException) { }
        try {
            (editor.rows as MutableList<*>).clear()
            fail("row projection must be immutable")
        } catch (_: UnsupportedOperationException) { }
        val other = VisualEditorState.create(editor.currentSource, "root")
        assertEquals(projection, other.programDisplayNodes())
        assertNotSame(projection, other.programDisplayNodes())
        editor.insertNoop()
        assertEquals(1, projection.size)
        assertEquals(1, other.rows.size)
        assertEquals(2, editor.rows.size)
    }

    @Test fun readonlyInvalidSourceStillHasStableFallbackWithoutEnablingMutation() {
        val editor = VisualEditorState.create("not-json\n", "root")
        assertTrue(editor.isReadOnly)
        val rows = editor.rows
        val projection = editor.programDisplayNodes()
        assertSame(rows, editor.rows)
        assertSame(projection, editor.programDisplayNodes())
        assertNull(editor.insertNoop())
        assertEquals("not-json\n", editor.currentSource)
    }

    @Test fun variableCandidatesComeFromOneDetachedProjectionAndDoNotChangeSelection() {
        val editor = editor()
        editor.insertBlock(requireNotNull(BlockCatalog.find("variable.set")), JsonObject().apply {
            addProperty("name", "数量"); addProperty("value", 1)
        })
        editor.insertBlock(requireNotNull(BlockCatalog.find("variable.copy")), JsonObject().apply {
            addProperty("name", "结果"); addProperty("sourceName", "来源")
        })
        editor.insertBlock(requireNotNull(BlockCatalog.find("task.runprompt")), JsonObject().apply {
            addProperty("message", "字面文字"); addProperty("valueVariable", "状态")
        })
        val source = editor.currentSource
        val selection = editor.selectedNodeIds
        assertEquals(setOf("数量", "结果", "来源", "状态"), visualKnownVariables(editor, emptyList(), "main").toSet())
        assertEquals(source, editor.currentSource)
        assertEquals(selection, editor.selectedNodeIds)
        assertTrue(visualKnownVariables(VisualEditorState.create("not-json\n", "root"), emptyList(), "main").isEmpty())
    }
}
