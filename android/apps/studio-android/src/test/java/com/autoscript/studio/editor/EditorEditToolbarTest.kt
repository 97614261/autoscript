package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorEditToolbarTest {
    @Test fun allExistingActionsFitOneFlatGridWithoutOmission() {
        assertEquals(19, EditorEditTool.entries.size)
        assertEquals(19, EditorEditTool.entries.map { it.title }.distinct().size)
        for (columns in 2..9) {
            val rows = EditorEditTool.entries.chunked(columns)
            assertEquals(EditorEditTool.entries.toList(), rows.flatten())
            assertTrue(rows.all { it.size in 1..columns })
        }
        assertEquals(4, EditorEditTool.entries.chunked(editorEditColumns(315f, 1f)).size)
        assertEquals("添加说明", EditorEditTool.ANNOTATE.title)
        assertEquals("注释执行", EditorEditTool.TOGGLE_DISABLED.title)
        assertEquals("以此为模板", EditorEditTool.REUSE.title)
    }

    @Test fun narrowWindowsAndLargeFontsUseFewerColumns() {
        assertEquals(5, editorEditColumns(330f, 1f))
        assertEquals(5, editorEditColumns(315f, 1f))
        assertEquals(4, editorEditColumns(280f, 1f))
        assertEquals(3, editorEditColumns(220f, 1f))
        assertEquals(2, editorEditColumns(330f, 2f))
        assertEquals(5, editorEditColumns(700f, 2f))
        assertEquals(9, editorEditColumns(700f, 1f))
        assertEquals(2, editorEditColumns(80f, 1f))
    }
}
