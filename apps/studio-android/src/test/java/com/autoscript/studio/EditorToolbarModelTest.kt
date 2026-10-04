package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorToolbarModelTest {
    private fun toolbar(
        running: Boolean = false,
        paused: Boolean = false,
        editing: Boolean = true,
        run: Boolean = true,
        resume: Boolean = true,
        step: Boolean = true,
        source: Boolean = true,
    ) = editorToolbarEntries(running, paused, run, resume, step, source, editing)

    @Test fun footerGroupsAllEditingCommandsIntoOneEntry() {
        assertEquals(listOf("运行", "单步", "编辑", "日志", "更多"), toolbar().map { it.action.label })
        assertEquals(listOf("撤销", "重做", "复制", "粘贴", "删除", "上移", "下移", "多选"),
            editorEditingEntries(1, true, true, true, true).map { it.action.label })
    }

    @Test fun narrowWindowsWrapAllActionsInsteadOfHidingAny() {
        for (width in listOf(180f, 210f, 232f, 292f, 600f, Float.NaN)) {
            val columns = editorToolbarColumns(width)
            assertTrue(columns in 3..5)
            val rows = toolbar().chunked(columns)
            assertEquals(toolbar(), rows.flatten())
        }
        assertEquals(1, toolbar().chunked(editorToolbarColumns(292f)).size)
        assertEquals(1, toolbar().chunked(editorToolbarColumns(232f)).size)
    }

    @Test fun runningAndPausedAlwaysKeepStopEvenOnNarrowWindows() {
        assertEquals(listOf("暂停", "单步", "停止", "日志", "更多"), toolbar(running = true).map { it.action.label })
        assertEquals(listOf("继续", "单步", "停止", "日志", "更多"), toolbar(paused = true).map { it.action.label })
        assertEquals("停止", toolbar(running = true).chunked(editorToolbarColumns(180f)).first().last().action.label)
        assertFalse(toolbar(paused = true, resume = false).first().enabled)
        assertFalse(toolbar(running = true, run = false).first { it.action == EditorToolbarAction.STOP }.enabled)
    }

    @Test fun editMenuIsAccessibleWithoutSelectionButNotWhileReadonlyOrRunning() {
        val readOnly = toolbar(editing = false)
        assertFalse(readOnly.first { it.action == EditorToolbarAction.EDIT }.enabled)
        assertTrue(toolbar().first { it.action == EditorToolbarAction.EDIT }.enabled)
        assertFalse(toolbar(running = true).any { it.action == EditorToolbarAction.EDIT })
        assertFalse(toolbar(paused = true).any { it.action == EditorToolbarAction.EDIT })
        assertFalse(toolbar(source = false).first { it.action == EditorToolbarAction.STEP }.enabled)
        assertTrue(toolbar(source = false).first { it.action == EditorToolbarAction.MORE }.enabled)
    }

    @Test fun groupedEditingCommandsRespectSelectionHistoryClipboardAndHostSupport() {
        fun entries(selection: Int = 0, undo: Boolean = true, redo: Boolean = true,
            paste: Boolean = true, commands: Boolean = true) =
            editorEditingEntries(selection, undo, redo, paste, commands).associate { it.action to it.enabled }
        val empty = entries()
        assertTrue(empty.getValue(EditorToolbarAction.UNDO))
        assertTrue(empty.getValue(EditorToolbarAction.PASTE))
        assertTrue(empty.getValue(EditorToolbarAction.ORGANIZE))
        for (action in listOf(EditorToolbarAction.COPY, EditorToolbarAction.DELETE,
            EditorToolbarAction.MOVE_UP, EditorToolbarAction.MOVE_DOWN)) {
            assertFalse(empty.getValue(action))
            assertTrue(entries(selection = 2).getValue(action))
        }
        assertFalse(entries(undo = false).getValue(EditorToolbarAction.UNDO))
        assertFalse(entries(redo = false).getValue(EditorToolbarAction.REDO))
        assertFalse(entries(paste = false).getValue(EditorToolbarAction.PASTE))
        val unsupported = entries(selection = 1, commands = false)
        assertTrue(unsupported.getValue(EditorToolbarAction.DELETE))
        assertTrue(unsupported.filterKeys { it != EditorToolbarAction.DELETE }.values.none { it })
    }

    @Test fun failureBadgeUsesLifecycleDiagnosticsNotAnyUserMentionOfError() {
        assertTrue(editorConsoleLineIsFailure("Runner · 失败"))
        assertTrue(editorConsoleLineIsFailure("诊断：ROOT_DISCONNECTED"))
        assertFalse(editorConsoleLineIsFailure("脚本/INFO: 失败重试完成"))
    }
}
