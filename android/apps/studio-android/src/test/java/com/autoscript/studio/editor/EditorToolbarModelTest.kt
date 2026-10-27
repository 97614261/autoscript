package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorToolbarModelTest {
    @Test fun sidebarLabelsDescribeTheirToolsAndAiRemainsVisible() {
        val content = java.io.File("src/main/java/com/autoscript/studio/editor/EditorDockContent.kt").readText()
        val actions = Regex("EditorDockAction\\(\"([^\"]+)\"").findAll(content).map { it.groupValues[1] }.toList()
        assertEquals(listOf("变量", "触控", "图像", "条件", "循环", "流程", "输出", "函数", "AI"), actions)
        val button = content.substringAfter("internal fun EditorDockAction(").substringBefore("internal fun EditorDockHeaderButton(")
        assertTrue(button.contains(".heightIn(min = 27.dp)"))
        assertFalse(button.contains(".height(27.dp)"))
        assertTrue(button.contains(".border(1.dp, EditorBorder"))
        assertFalse(button.contains("EditorToolButton("))
        assertFalse(button.contains(".padding(vertical = 4.dp)"))
    }

    @Test fun everyEditorCommandHasARealToolbarProducer() {
        val dock = editorDockTestSources()
        val emitted = Regex("onCommand\\(EditorProgramCommand\\.(\\w+)\\)")
            .findAll(dock).map { it.groupValues[1] }.toSet()
        assertEquals(EditorProgramCommand.entries.map { it.name }.toSet(), emitted)
    }

    @Test fun stopRemainsIndependentOfOrdinaryControlBusy() {
        val entries = editorToolbarEntries(true, false, false, false, false, true, false, stopEnabled = true)
        assertEquals(EditorToolbarAction.STOP, entries.first().action)
        assertTrue(entries.first().enabled)
        assertFalse(entries.first { it.action == EditorToolbarAction.PAUSE }.enabled)
        assertTrue(entries.first { it.action == EditorToolbarAction.STOP }.enabled)
        val paused = com.autoscript.core.model.RuntimeEngineState.PAUSED
        assertTrue(editorCanStop(true, paused, false))
        assertFalse(editorCanStop(true, paused, true))
        assertFalse(editorCanStop(false, paused, false))
        assertFalse(editorCanStop(true, com.autoscript.core.model.RuntimeEngineState.STOPPING, false))
        assertFalse(editorCanStop(true, com.autoscript.core.model.RuntimeEngineState.IDLE, false))
    }
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
        assertEquals(listOf("停止", "单步", "暂停", "日志", "更多"), toolbar(running = true).map { it.action.label })
        assertEquals(listOf("停止", "单步", "继续", "日志", "更多"), toolbar(paused = true).map { it.action.label })
        assertEquals("停止", toolbar(running = true).chunked(editorToolbarColumns(180f)).first().first().action.label)
        assertFalse(toolbar(paused = true, resume = false).first { it.action == EditorToolbarAction.RESUME }.enabled)
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

    @Test fun failureBadgeUsesLifecycleDiagnosticsNotAnyUserMentionOfError() {
        assertTrue(editorConsoleLineIsFailure("Runner · 失败"))
        assertTrue(editorConsoleLineIsFailure("诊断：ROOT_DISCONNECTED"))
        assertFalse(editorConsoleLineIsFailure("脚本/INFO: 失败重试完成"))
    }
}
