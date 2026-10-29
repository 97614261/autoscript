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

    @Test fun inheritedDisableOnlyMutesRowsWithoutRepeatingAPrefix() {
        val inherited = compactProgramRowText("[随父级禁用] 弹出提示 · 内容")
        assertEquals("", inherited.disabledPrefix)
        assertEquals("弹出提示", inherited.title)
        assertTrue(inherited.disabled)
        assertTrue(inherited.inheritedDisabled)
        val own = compactProgramRowText("[已禁用] 条件判断 · 数量 > 0")
        assertEquals("已注释  ", own.disabledPrefix)
        assertTrue(own.disabled)
        assertEquals("", compactProgramRowText("点击 · (1, 2)").disabledPrefix)
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

    @Test fun compactBranchPrefixesOnlyOmitInformationAlreadyVisibleInTheTree() {
        assertNull(compactProgramBranchLabel("循环体"))
        assertNull(compactProgramBranchLabel("成功分支", hasHeading = true))
        assertNull(compactProgramBranchLabel("失败分支", hasHeading = true))
        // Legacy callers without structured heading metadata retain their prefixes.
        assertEquals("成功", compactProgramBranchLabel("成功分支"))
        assertEquals("失败", compactProgramBranchLabel("失败分支"))
        assertEquals("满足", compactProgramBranchLabel("满足"))
        assertEquals("否则", compactProgramBranchLabel("否则"))
        assertEquals("否则若2", compactProgramBranchLabel("否则如果 2"))
        assertNull(compactProgramBranchLabel("满足", hasHeading = true))
        assertNull(compactProgramBranchLabel("否则", hasHeading = true))
    }

    @Test fun compactRenderingRetainsFullLabelsForAccessibilityAndSeparateBranchAnchors() {
        val source = editorDockTestSources()
        assertTrue(source.contains("compactProgramBranchLabel(parts.branch, parts.branchHasHeading)"))
        assertTrue(source.contains("contentDescription = node.label"))
        assertTrue(source.contains("选择\${childBlockLabel(branch.slot)}作为加入位置"))
    }

    @Test fun compactRowsShareReducedHeightWithoutShrinkingText() {
        assertEquals(28, EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP)
        assertEquals(3, EDITOR_PROGRAM_ROW_PADDING_DP)
        assertTrue(EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP >= 16 + 2 * EDITOR_PROGRAM_ROW_PADDING_DP)
        val source = editorDockTestSources()
        assertEquals(5, Regex("heightIn\\(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp\\)").findAll(source).count())
        assertTrue(source.contains("fontSize = 12.sp, lineHeight = 16.sp, maxLines = if (expandedSummary) Int.MAX_VALUE else 1"))
        assertFalse(source.contains("height(EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)"))
    }

    @Test fun compactRowsKeepClickAndExecutionHighlightWiring() {
        val source = editorDockTestSources()
        assertTrue(source.contains("internal fun EditorProgramTreeRow("))
        val treeRow = source.substringAfter("internal fun EditorProgramTreeRow(").substringBefore("internal fun EditorProgramSearchDialog(")
        assertTrue(treeRow.contains("onClick = onSelect, onLongClick = onLongClick"))
        assertTrue(treeRow.contains("clickable(enabled = hasChildren, onClick = onToggle)"))
        assertTrue(treeRow.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue(treeRow.contains("editorExecutionEmphasis(current, executing, recent)"))
        assertTrue(treeRow.contains("emphasis.label"))
        assertTrue(treeRow.contains("emphasis.background"))
        assertTrue(treeRow.contains("emphasis.accent"))
    }

    @Test fun executionMarkerUsesFixedGutterInsteadOfChangingLabelText() {
        val source = editorDockTestSources()
        val row = source.substringAfter("internal fun EditorProgramTreeRow(").substringBefore("internal fun EditorProgramLabel(")
        val label = source.substringAfter("internal fun EditorProgramLabel(").substringBefore("internal fun EditorProgramSearchDialog(")
        assertTrue(row.contains("width(EDITOR_TREE_EXPAND_WIDTH_DP.dp)"))
        assertTrue(row.contains("drawPath(marker, emphasis.accent)"))
        assertTrue(row.contains("clickable(enabled = hasChildren, onClick = onToggle)"))
        assertFalse(label.contains("current"))
        assertFalse(label.contains("emphasis"))
        assertFalse(label.contains("▶"))
    }
}
