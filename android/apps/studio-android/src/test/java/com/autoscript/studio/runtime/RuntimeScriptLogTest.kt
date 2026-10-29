package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogWriter
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.api.RuntimeScriptLog
import com.autoscript.runtime.api.RuntimeScriptLogState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeScriptLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun elapsedClockIsRenderedAsReadableTimeWithoutChangingTheRecord() {
        val raw = "1000ms · 脚本执行/START: 脚本开始运行"
        val wall = 1_700_000_001_000L
        val expected = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(wall))
        assertEquals(expected, runtimeLogLinePresentation(raw, wall - 1000).time)
        assertEquals("0:01", runtimeLogLinePresentation(raw).time)
        assertEquals("脚本开始运行", runtimeLogLinePresentation(raw, wall - 1000).message)
        assertTrue(raw.startsWith("1000ms"))
    }

    @Test fun acceptedRunRecordsNormalCompletionWithoutAnyOutputNodes() {
        val state = RuntimeScriptLogState()
        assertNull(state.transition(RuntimeProtocol.STATE_IDLE, 1, false))
        assertTrue(state.start(10).contains("脚本开始运行"))
        repeat(3) { assertNull(state.transition(RuntimeProtocol.STATE_RUNNING, 11, false)) }
        assertTrue(state.transition(RuntimeProtocol.STATE_PAUSED, 12, false)!!.contains("脚本已暂停"))
        assertTrue(state.transition(RuntimeProtocol.STATE_RUNNING, 13, false)!!.contains("脚本继续运行"))
        assertTrue(state.transition(RuntimeProtocol.STATE_STOPPED, 14, false)!!.contains("脚本运行完成"))
        assertNull(state.transition(RuntimeProtocol.STATE_STOPPED, 15, false))
        assertNull(state.transition(RuntimeProtocol.STATE_FAILED, 16, false))
    }

    @Test fun manualStopFailureRestartAndShortStepRemainDistinct() {
        val state = RuntimeScriptLogState()
        state.start(1)
        state.transition(RuntimeProtocol.STATE_PAUSED, 2, false)
        assertTrue(state.transition(RuntimeProtocol.STATE_PAUSED, 3, false, stepPause = true)!!.contains("单步完成"))
        assertNull(state.transition(RuntimeProtocol.STATE_STOPPING, 4, true))
        assertTrue(state.transition(RuntimeProtocol.STATE_STOPPED, 5, true)!!.contains("脚本已停止"))
        assertTrue(state.start(6, restarted = true).contains("重新开始"))
        val failed = state.transition(RuntimeProtocol.STATE_FAILED, 7, false, failureCode = "LUA_RUNTIME_ERROR")!!
        assertTrue(editorConsoleLineIsFailure(failed))
        assertTrue(failed.contains("LUA_RUNTIME_ERROR"))
        assertNull(state.transition(RuntimeProtocol.STATE_FAILED, 8, false))
    }

    @Test fun liveAndHistoryUseOnlyRealTraceIdentitiesWithoutSourceOrValues() {
        val event = """{"version":1,"kind":"trace","phase":"enter","flowId":"flow-1","nodeId":"node-12","receivedAtElapsedMs":22,"source":"private source","value":"private value"}"""
        val row = RuntimeScriptLog.observation(event)!!
        assertEquals("22ms · 脚本执行/TRACE: 插件 flow-1 · 积木 node-12", row)
        assertEquals(RuntimeConsoleChannel.EXECUTION, runtimeConsoleChannel(row))
        val display = runtimeLogLinePresentation(row)
        assertEquals("积木", display.label)
        assertFalse(display.failure)
        assertFalse(row.contains("private"))
        assertNull(RuntimeScriptLog.observation(event.replace("flow-1", "private/path")))
        assertNull(RuntimeScriptLog.observation(event.replace("\"enter\"", "\"unknown\"")))
        assertNull(RuntimeScriptLog.observation(event.replace("\"version\":1", "\"version\":2")))
        assertNull(RuntimeScriptLog.observation(event.replace(",\"receivedAtElapsedMs\":22", "")))
        assertNull(RuntimeScriptLog.observation("not-json"))
    }

    @Test fun failureAndLossRemainVisibleWithoutTurningSuccessfulTaskRecordsIntoErrors() {
        val event = """{"version":1,"kind":"task","flowId":"main","nodeId":"node-3","status":"failed","receivedAtElapsedMs":23}"""
        assertTrue(editorConsoleLineIsFailure(RuntimeScriptLog.observation(event)!!))
        assertNull(RuntimeScriptLog.observation(event.replace("failed", "completed")))
        val dropped = """{"version":1,"kind":"dropped","count":9,"eventCount":7,"scriptLogCount":2,"receivedAtElapsedMs":24}"""
        val warning = RuntimeScriptLog.observation(dropped)!!
        assertTrue(warning.contains("观测丢失 7 条"))
        assertTrue(warning.contains("输出丢失 2 条"))
        assertTrue(runtimeLogLinePresentation(warning).warning)
        assertFalse(editorConsoleLineIsFailure(warning))
    }

    @Test fun historyMergesChronologicallyPreservesRepeatedOutputAndExcludesPerformancePayloads() {
        val script = "10ms · 脚本执行/START: 脚本开始运行\n22ms · 脚本/INFO: same\n22ms · 脚本/INFO: same\n30ms · 脚本执行/END: 脚本运行完成"
        val trace = """{"version":1,"kind":"trace","phase":"enter","flowId":"main","nodeId":"node-1","receivedAtElapsedMs":20}"""
        val vision = """{"version":1,"kind":"vision","status":"error","receivedAtElapsedMs":21}"""
        val lines = RuntimeScriptLog.history(script, "$trace\n$vision")
        assertEquals(5, lines.size)
        assertTrue(lines[1].contains("node-1"))
        assertEquals(lines[2], lines[3])
        assertTrue(lines.last().contains("运行完成"))
        assertEquals(lines, filterRuntimeConsole(lines, "", false, RuntimeConsoleChannel.ESSENTIAL))
        assertEquals(listOf(lines[2], lines[3]), filterRuntimeConsole(lines, "", false, RuntimeConsoleChannel.SCRIPT))
        assertEquals(script.lines(), RuntimeScriptLog.history(script, ""))
    }

    @Test fun historyProjectionRespondsToCancellation() {
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            RuntimeScriptLog.history("", List(1024) { "{}" }.joinToString("\n")) {
                if (++checks == 3) throw kotlinx.coroutines.CancellationException("changed run")
            }
        }
        assertEquals(3, checks)
    }

    @Test fun lifecycleOutputAndTerminalTraceSurviveRealFileWriterRoundtrip() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(mapOf("projectId" to "qa"))!!
        val state = RuntimeScriptLogState()
        writer.append("script.log", state.start(10))
        writer.append("script.log", "15ms · 脚本/INFO: value=123")
        writer.append("events.jsonl", """{"version":1,"kind":"task","flowId":"main","nodeId":"node-2","status":"failed","receivedAtElapsedMs":20}""")
        writer.append("script.log", state.transition(RuntimeProtocol.STATE_FAILED, 21, false)!!)
        writer.end("failed"); writer.close()
        assertTrue(writer.awaitClosed(5000))
        val events = RuntimeLogFiles.read(root, id, "events.jsonl")
        val lines = RuntimeScriptLog.history(RuntimeLogFiles.read(root, id, "script.log"), events)
        assertEquals(4, lines.size)
        assertTrue(lines[2].contains("node-2"))
        assertEquals(2, filterRuntimeConsole(lines, "", true, RuntimeConsoleChannel.ESSENTIAL).size)
        assertFalse(writer.failureCode, writer.failure)
    }
}
