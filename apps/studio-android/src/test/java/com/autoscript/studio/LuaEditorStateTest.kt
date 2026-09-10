package com.autoscript.studio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaEditorStateTest {
    @Test
    fun dirtyStateTracksTheLastPersistedText() {
        var buffer = LuaEditorBuffer.open("project-1", "old")
        assertFalse(buffer.isDirty)

        buffer = buffer.edit("new")
        assertTrue(buffer.isDirty)

        buffer = buffer.markSaved("new")
        assertFalse(buffer.isDirty)
    }

    @Test
    fun saveCompletionDoesNotEraseEditsMadeWhileSaving() {
        var buffer = LuaEditorBuffer.open("project-1", "one").edit("two")
        val submitted = buffer.text
        buffer = buffer.edit("three")

        buffer = buffer.markSaved(submitted)

        assertEquals("three", buffer.text)
        assertEquals("two", buffer.savedText)
        assertTrue(buffer.isDirty)
    }

    @Test
    fun indentReplacesSelectionAndMovesCursor() {
        val result = insertEditorIndent(
            TextFieldValue("abc", selection = TextRange(1, 3)),
            indent = "  ",
        )

        assertEquals("a  ", result.text)
        assertEquals(TextRange(3), result.selection)
    }
}
