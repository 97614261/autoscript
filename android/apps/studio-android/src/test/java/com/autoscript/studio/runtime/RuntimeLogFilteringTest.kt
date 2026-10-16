package com.autoscript.studio

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeLogFilteringTest {
    private val script = "12ms · 脚本调试日志 · 脚本/ERROR: Root failed"
    private val diagnostic = "13ms · Runner状态 · 运行中 · Root 已认证"
    private val local = "[12:34:56] 诊断：ROOT_NOT_READY"
    private val unknown = "old-format: script result"

    @Test fun classificationUsesChannelPrefixNotTheMessageWords() {
        assertEquals(RuntimeConsoleChannel.SCRIPT, runtimeConsoleChannel(script))
        assertEquals(RuntimeConsoleChannel.SCRIPT, runtimeConsoleChannel("1ms · 脚本/INFO: Runner状态 · idle"))
        assertEquals(RuntimeConsoleChannel.DIAGNOSTIC, runtimeConsoleChannel(diagnostic))
        assertEquals(RuntimeConsoleChannel.DIAGNOSTIC, runtimeConsoleChannel(local))
        assertEquals(RuntimeConsoleChannel.OTHER, runtimeConsoleChannel(unknown))
        assertEquals(RuntimeConsoleChannel.OTHER, runtimeConsoleChannel("text · Runner状态 · unknown"))
        assertTrue(editorConsoleLineIsFailure(script))
        assertTrue(editorConsoleLineIsFailure("12ms · 脚本/ERROR: failure"))
        assertFalse(editorConsoleLineIsFailure("12ms · 脚本/INFO: contains 脚本/ERROR: as text"))
    }

    @Test fun channelsSearchAndFailureFiltersComposeWithoutChangingOriginals() {
        val lines = listOf(script, diagnostic, local, unknown)
        assertEquals(lines, filterRuntimeConsole(lines, "", false))
        assertEquals(listOf(script), filterRuntimeConsole(lines, "root", false, RuntimeConsoleChannel.SCRIPT))
        assertEquals(listOf(diagnostic, local), filterRuntimeConsole(lines, "", false, RuntimeConsoleChannel.DIAGNOSTIC))
        assertEquals(listOf(script), filterRuntimeConsole(lines, "", true, RuntimeConsoleChannel.SCRIPT))
        assertEquals(listOf(unknown), filterRuntimeConsole(lines, "", false, RuntimeConsoleChannel.OTHER))
        assertEquals(emptyList<String>(), filterRuntimeConsole(lines, "absent", false))
        assertEquals(listOf(script, diagnostic, local, unknown), lines)
    }

    @Test fun immutableSnapshotChangesEvenWhenSourceIdentityDoesNot() {
        val lines = mutableStateListOf(script)
        val previous = lines.toList()
        lines += diagnostic
        val next = lines.toList()
        assertNotEquals(previous, next)
        assertEquals(listOf(script), previous)
        assertEquals(listOf(script, diagnostic), filterRuntimeConsole(next, "", false))
        val ui = File("src/main/java/com/autoscript/studio/runtime/RuntimeLogDialog.kt").readText()
        assertTrue(ui.contains("val snapshot = lines.toList()"))
        assertTrue(ui.contains("remember(snapshot, query, failuresOnly, channel, history)"))
        assertTrue(ui.contains("LogLineDetailDialog(selectedLine"))
    }
}
