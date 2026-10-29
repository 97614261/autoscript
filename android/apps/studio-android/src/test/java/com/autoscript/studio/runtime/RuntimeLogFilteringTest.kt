package com.autoscript.studio

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.*
import org.junit.Test

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
        assertEquals(RuntimeConsoleChannel.DIAGNOSTIC, runtimeConsoleChannel("1792542000000 · App诊断 · CAPTURE_PIPE_READ"))
        assertEquals(RuntimeConsoleChannel.DIAGNOSTIC, runtimeConsoleChannel("1792542000000ms · App诊断 · ROOT_START"))
        assertEquals(RuntimeConsoleChannel.OTHER, runtimeConsoleChannel("user text · App诊断 · ROOT_START"))
        assertEquals(RuntimeConsoleChannel.OTHER, runtimeConsoleChannel(unknown))
        assertEquals(RuntimeConsoleChannel.OTHER, runtimeConsoleChannel("text · Runner状态 · unknown"))
        assertTrue(editorConsoleLineIsFailure(script))
        assertTrue(editorConsoleLineIsFailure("12ms · 脚本/ERROR: failure"))
        assertFalse(editorConsoleLineIsFailure("12ms · 脚本/INFO: contains 脚本/ERROR: as text"))
        assertFalse(editorConsoleLineIsFailure("12ms · 脚本/INFO: 用户文字：诊断：失败"))
        assertFalse(editorConsoleLineIsFailure("[12:34:56] 诊断：插件 flow-1 运行中"))
        assertTrue(editorConsoleLineIsFailure("13ms · Runner诊断 · 截图预览失败：后端不可用"))
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
        assertEquals(listOf(script), filterRuntimeConsole(next, "", false, RuntimeConsoleChannel.ESSENTIAL))
    }

    @Test fun essentialKeepsScriptOutputFailuresAndUnclassifiedLegacyLinesWithoutMutatingRawRecords() {
        val repeated = "14ms · 脚本/INFO: same result"
        val records = listOf(diagnostic, script, local, unknown, repeated, repeated,
            "[12:35:00] 引擎 已停止", "15ms · Runner诊断 · ROOT_STOP_CLEANUP_FAILED")
        assertEquals(listOf(script, local, unknown, repeated, repeated, records.last()),
            filterRuntimeConsole(records, "", false, RuntimeConsoleChannel.ESSENTIAL))
        assertEquals(records, filterRuntimeConsole(records, "", false))
        assertEquals(listOf(script, local, records.last()), filterRuntimeConsole(records, "", true, RuntimeConsoleChannel.ESSENTIAL))
    }

    @Test fun compactPresentationRemovesOnlyKnownPrefixesAndPreservesMessageContent() {
        val message = "Runner状态 · 结果=100(+100) 180\nsecond line"
        val item = runtimeLogLinePresentation("12ms · 脚本调试日志 · 脚本/INFO: $message")
        assertEquals(message, item.message)
        assertEquals("0:00", item.time)
        assertFalse(item.failure)
        assertFalse(item.warning)
        assertEquals("retry", runtimeLogLinePresentation("[09:05:52] 脚本/WARN: retry").message)
        assertTrue(runtimeLogLinePresentation("[09:05:52] 脚本/WARN: retry").warning)
        assertTrue(runtimeLogLinePresentation(script).failure)
        assertEquals("14:36", runtimeLogLinePresentation("876670ms · Runner诊断 · CAPTURE_BACKEND_FAILED").time)
        assertEquals("CAPTURE_BACKEND_FAILED", runtimeLogLinePresentation("876670ms · Runner诊断 · CAPTURE_BACKEND_FAILED").message)
        assertEquals(unknown, runtimeLogLinePresentation(unknown).message)
        assertEquals("", runtimeLogLinePresentation(unknown).time)
        assertEquals("user text · App诊断 · ROOT_START", runtimeLogLinePresentation("user text · App诊断 · ROOT_START").message)
    }

    @Test fun backgroundHistoryFilteringHonorsCancellationDuringAndAfterScanning() {
        val lines = List(1024) { "line $it" }
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            filterRuntimeConsole(lines, "line", false) {
                if (++checks == 3) throw kotlinx.coroutines.CancellationException("new query")
            }
        }
        assertEquals(3, checks)
        var completed = 0
        assertEquals(lines, filterRuntimeConsole(lines, "line", false) { completed++ })
        assertEquals(9, completed)
        assertEquals(1024, lines.size)
    }
}
