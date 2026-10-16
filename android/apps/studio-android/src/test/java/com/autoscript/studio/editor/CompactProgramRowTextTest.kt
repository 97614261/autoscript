package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CompactProgramRowTextTest {
    @Test fun separatesTitleFromAllDetailsWithoutDiscardingText() {
        val result = compactProgramRowText("滑动 · (1, 2) → (3, 4) · 500ms")
        assertEquals("滑动", result.title)
        assertEquals("(1, 2) → (3, 4) · 500ms", result.details)
        assertFalse(result.disabled)
        assertNull(result.branch)
    }
    @Test fun separatesDisableAndBranchPrefixes() {
        val result = compactProgramRowText("[已禁用] [循环体] 点击 · (320, 640)")
        assertTrue(result.disabled)
        assertEquals("循环体", result.branch)
        assertEquals("点击", result.title)
        assertEquals("(320, 640)", result.details)
    }
    @Test fun bracketedParameterIsNotTreatedAsBranch() {
        val result = compactProgramRowText("注释 · [这里] 保留说明")
        assertNull(result.branch)
        assertEquals("[这里] 保留说明", result.details)
    }
    @Test fun plainAndEmptyRowsRemainDisplayable() {
        assertEquals("跳出循环", compactProgramRowText("跳出循环").title)
        assertEquals("", compactProgramRowText("").details)
    }

    @Test fun compactRowsShareReducedHeightWithoutShrinkingText() {
        assertEquals(28, EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP)
        assertEquals(3, EDITOR_PROGRAM_ROW_PADDING_DP)
        assertTrue(EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP >= 16 + 2 * EDITOR_PROGRAM_ROW_PADDING_DP)
        val source = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        assertEquals(5, Regex("heightIn\\(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp\\)").findAll(source).count())
        assertTrue(source.contains("fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1"))
        assertFalse(source.contains("height(EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)"))
    }

    @Test fun compactRowsKeepClickAndExecutionHighlightWiring() {
        val source = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        assertTrue(source.contains("internal fun EditorProgramTreeRow("))
        val treeRow = source.substringAfter("internal fun EditorProgramTreeRow(").substringBefore("private fun EditorProgramSearchDialog(")
        assertTrue(treeRow.contains("onClick = onSelect, onLongClick = onLongClick"))
        assertTrue(treeRow.contains("clickable(enabled = hasChildren, onClick = onToggle)"))
        assertTrue(treeRow.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue(treeRow.contains("editorExecutionEmphasis(current, executing, recent)"))
        assertTrue(treeRow.contains("emphasis.label"))
        assertTrue(treeRow.contains("emphasis.background"))
        assertTrue(treeRow.contains("emphasis.accent"))
    }
}
