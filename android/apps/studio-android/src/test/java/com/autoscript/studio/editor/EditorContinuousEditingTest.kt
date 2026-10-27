package com.autoscript.studio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorContinuousEditingTest {
    @Test fun saveAndValidationBusyDisableCommandsWithoutClosingTheWindow() {
        val source = editorDockTestSources()
        val lifecycle = source.substringAfter("LaunchedEffect(editingEnabled, running)").substringBefore("deleteRequest?.let")
        assertTrue(lifecycle.contains("if (running)"))
        assertFalse(lifecycle.contains("if (!editingEnabled)"))
        val operations = source.substringAfter("if (nodeEditorVisible) {").substringBefore("if (consoleVisible)")
        assertEquals("Only explicit dismiss may close the retained edit window", 1,
            Regex("nodeEditorVisible = false").findAll(operations).count())
        assertTrue(operations.contains("onDismiss = { nodeEditorVisible = false }"))
    }

    @Test fun indentationMovesTheWholeRowAndClampsInvalidDepths() {
        assertEquals(2, editorTreeRowIndent(0))
        assertEquals(10, editorTreeRowIndent(1))
        assertEquals(18, editorTreeRowIndent(2))
        assertEquals(2, editorTreeRowIndent(-100))
        assertEquals(258, editorTreeRowIndent(Int.MAX_VALUE))
        val source = editorDockTestSources()
        assertTrue(source.contains(".padding(start = editorTreeRowIndent(node.depth).dp)"))
    }

    @Test fun bothTreesUseNarrowAlignedColumnsWithoutChangingVerticalTargets() {
        assertEquals(16, EDITOR_TREE_EXPAND_WIDTH_DP)
        assertEquals(20, EDITOR_TREE_CHECK_WIDTH_DP)
        assertTrue(EDITOR_TREE_CHECK_WIDTH_DP >= 17)
        assertEquals(10, editorTreeGuideOffset(0))
        assertEquals(18, editorTreeGuideOffset(1))
        assertEquals(10, editorTreeGuideOffset(-1))
        assertEquals(258, editorTreeGuideOffset(Int.MAX_VALUE))
        for (depth in 0..31) assertEquals(editorTreeRowIndent(depth) + EDITOR_TREE_EXPAND_WIDTH_DP / 2, editorTreeGuideOffset(depth))
        val source = editorDockTestSources()
        assertEquals(2, Regex("start = editorTreeRowIndent\\(node.depth\\).dp").findAll(source).count())
        assertEquals(2, Regex("val x = editorTreeGuideOffset\\(level\\).dp.toPx\\(\\)").findAll(source).count())
        assertEquals(2, Regex("width\\(EDITOR_TREE_EXPAND_WIDTH_DP.dp\\)").findAll(source).count())
        assertTrue(source.contains("width(EDITOR_TREE_CHECK_WIDTH_DP.dp).heightIn(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)"))
    }

    @Test fun pastePickerOffersBeforeAfterAndEveryActualBranchWithoutDuplicates() {
        val options = editorPasteOptions(listOf("body", "then", "else", "body"))
        assertEquals(listOf("本行之前", "本行之后"), options.take(2).map { it.second })
        assertTrue(options.first().first.before)
        assertFalse(options[1].first.before)
        assertEquals(listOf("body", "then", "else"), options.drop(2).map { it.first.childSlot })
        assertEquals(2, editorPasteOptions(emptyList()).size)
    }

    @Test fun stalePasteRequestRejectsChangedSourceSelectionProjectOrFlow() {
        val editor = VisualEditorState.create("", "root")
        val request = EditorPasteRequest("project-1", "main", editor.currentSource, null, emptySet())
        assertTrue(request.matches("project-1", "main", editor))
        assertFalse(request.matches("project-2", "main", editor))
        assertFalse(request.matches("project-1", "other", editor))
        val inserted = requireNotNull(editor.insertNoop())
        assertFalse(request.matches("project-1", "main", editor))
        val current = EditorPasteRequest("project-1", "main", editor.currentSource, inserted, editor.selectedNodeIds)
        editor.selectOnly(null)
        assertFalse(current.matches("project-1", "main", editor))
    }

    @Test fun sharedTreesUseContentSizedHorizontalSpaceInsteadOfAFixedWideCanvas() {
        assertEquals(292f, editorTreeContentWidth(292f, 0), .01f)
        assertEquals(300f, editorTreeContentWidth(210f, 10), .01f)
        assertEquals(476f, editorTreeContentWidth(210f, Int.MAX_VALUE), .01f)
        assertEquals(800f, editorTreeContentWidth(800f, 32), .01f)
        assertEquals(220f, editorTreeContentWidth(Float.NaN, -1), .01f)
    }
}
