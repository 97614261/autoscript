package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class PluginSourceEditingTest {
    private fun editor(): VisualEditorState = VisualEditorState.create("", "block-root").apply { insertNoop() }

    @Test fun emptyPluginAcceptsFirstRealBlockAndUndoReturnsToEmpty() {
        val state=VisualEditorState.create("","block-root")
        assertTrue(state.rows.isEmpty())
        state.insertNoop()
        val source=state.currentSource
        assertEquals(1,state.rows.size)
        assertTrue(state.undo())
        assertEquals("",state.currentSource)
        assertTrue(state.redo())
        assertEquals(source,state.currentSource)
    }

    @Test fun rawSourceAppliesAsOneUndoableDraftWithoutMarkingSaved() {
        val editor = editor()
        val original = editor.currentSource
        val replacement = original.replace("\"args\":{}", "\"args\":{\"note\":\"edited\"}")
        assertNotEquals(original, replacement)
        assertNull(editor.replaceRawSource(original, replacement))
        assertTrue(editor.isDirty)
        assertEquals("edited", editor.nodeArguments(editor.rows.single().nodeId)?.get("note")?.asString)
        assertTrue(editor.undo())
        assertEquals(original, editor.currentSource)
        assertTrue(editor.redo())
        assertEquals(replacement, editor.currentSource)
    }

    @Test fun staleOrInvalidReplacementCannotOverwriteDraft() {
        val editor = editor()
        val source = editor.currentSource
        assertNotNull(editor.replaceRawSource("stale", ""))
        assertNotNull(editor.replaceRawSource(source, "not json"))
        assertEquals(source, editor.currentSource)
        assertNotNull(editor.replaceRawSource(source, source + source))
        assertEquals(source, editor.currentSource)
    }

    @Test fun replacementCanClearProgramAndSelectionAndBeUndone() {
        val editor = editor()
        val source = editor.currentSource
        assertNull(editor.replaceRawSource(source, ""))
        assertTrue(editor.rows.isEmpty())
        assertTrue(editor.selectedNodeIds.isEmpty())
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
    }

    @Test fun oversizedOrWrongRootReplacementIsRejectedWithoutMutation() {
        val editor = editor()
        val source = editor.currentSource
        assertNotNull(editor.replaceRawSource(source, "x".repeat(1_048_577)))
        assertNotNull(editor.replaceRawSource(source, source.replace("block-root", "other-root")))
        assertEquals(source, editor.currentSource)
    }
}
