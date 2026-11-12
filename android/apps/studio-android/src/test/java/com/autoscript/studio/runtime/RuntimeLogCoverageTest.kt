package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.runtime.api.RuntimeScriptLog
import com.autoscript.runtime.api.RuntimeSystemLog
import com.autoscript.runtime.api.RuntimeSystemMessages
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeLogCoverageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun asynchronousStatesAndGenericResultsRemainReadableAndSearchable() {
        for ((status, expected) in listOf("waiting" to "等待中", "requested" to "已提交请求", "disabled" to "已关闭，未显示")) {
            val event = JsonObject().apply {
                addProperty("version", 1); addProperty("kind", "action"); addProperty("receivedAtElapsedMs", 10)
                addProperty("operation", "等待界面操作"); addProperty("status", status)
            }
            val line = RuntimeScriptLog.observation(event.toString())!!
            assertTrue(runtimeLogLinePresentation(line).message.contains(expected))
            assertFalse(editorConsoleLineIsFailure(line))
            assertEquals(listOf(line), filterRuntimeConsole(listOf(line), expected, false, RuntimeConsoleChannel.ACTIVITY))
        }
        val failed = JsonObject().apply {
            addProperty("version", 1); addProperty("kind", "action"); addProperty("receivedAtElapsedMs", 11)
            addProperty("operation", "读取文件"); addProperty("details", "路径=test.txt")
            addProperty("status", "error"); addProperty("failureCode", "HOST_DISPATCH_FAILED")
        }
        val line = RuntimeScriptLog.observation(failed.toString())!!
        assertTrue(editorConsoleLineIsFailure(line))
        assertTrue(runtimeLogLinePresentation(line).message.contains("HOST_DISPATCH_FAILED"))
        assertEquals(listOf(line), filterRuntimeConsole(listOf(line), "", false, RuntimeConsoleChannel.SYSTEM))
    }

    @Test fun translatedDiagnosticsKeepNormalRootReleaseSeparateFromActualFailure() {
        for (code in listOf("ROOT_IDLE_RELEASED", "ROOT_DISCONNECTED", "CAPTURE_CANCELLED")) {
            val line = "123ms · App诊断 · $code"
            assertFalse(editorConsoleLineIsFailure(line))
            assertFalse(runtimeLogLinePresentation(line).message.startsWith(code))
        }
        for (code in listOf("CAPTURE_PROCESS_START", "CAPTURE_BACKEND_FAILED", "ROOT_KEY_FAILED")) {
            val line = "123ms · App诊断 · $code"
            assertTrue(editorConsoleLineIsFailure(line))
            assertTrue(runtimeLogLinePresentation(line).message.contains("[$code]"))
        }
        val output = "123ms · 脚本/INFO: App诊断 · ROOT_KEY_FAILED"
        assertFalse(editorConsoleLineIsFailure(output))
        assertEquals("App诊断 · ROOT_KEY_FAILED", runtimeLogLinePresentation(output).message)
        assertNull(RuntimeSystemMessages.readableDiagnostic("prefix App诊断 · ROOT_KEY_FAILED"))
    }

    @Test fun asyncSystemNoticesPersistAndExplicitInfoCannotBecomeAnErrorByItsText() {
        val files = temporary.newFolder()
        RuntimeSystemLog.notice(files, "项目管理", "保存成功")
        RuntimeSystemLog.notice(files, "服务器备份", "连接超时，操作结果未确认")
        RuntimeSystemLog.notice(files, "运行连接", "重试后恢复，上次失败", failure = false)
        assertTrue(RuntimeSystemLog.flush())
        val records = RuntimeSystemLog.read(files)
        assertEquals(3, records.size)
        assertFalse(editorConsoleLineIsFailure(records[0]))
        assertTrue(editorConsoleLineIsFailure(records[1]))
        assertFalse(editorConsoleLineIsFailure(records[2]))
        assertEquals(records, filterRuntimeConsole(records, "", false, RuntimeConsoleChannel.SYSTEM))
        assertTrue(filterRuntimeConsole(records, "", false, RuntimeConsoleChannel.ACTIVITY).isEmpty())
    }

    @Test fun onlyStateDeltasArePersistedRemotePollingDoesNotReplayThem() {
        val persisted = mutableListOf<String>()
        val log = RuntimeConsoleLog(now = { "12:00:00" }, onStateDelta = { persisted.add(it) })
        val connected = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED)
        log.record(connected)
        val count = persisted.size
        assertTrue(count > 0)
        log.record(connected)
        log.recordRemote(listOf("10ms · App诊断 · ROOT_READY"))
        log.recordRemote(listOf("10ms · App诊断 · ROOT_READY"))
        assertEquals(count, persisted.size)
    }

    @Test fun liveMirrorsAreHiddenButRepeatedScriptActionsAreKept() {
        val saved = listOf("[12:00:00] 系统/INFO: 运行连接 · Runner 已断开",
            "[12:00:01] 系统/INFO: Root 已连接并验证 [ROOT_READY]")
        val action = "12ms · 脚本动作/ERROR: 点击 · 失败"
        val live = listOf("[12:00:00] Runner 已断开", "11ms · App诊断 · ROOT_READY", action, action)
        assertEquals(saved + listOf(action, action), runtimeLogCurrentSystemLines(saved, live))
    }

    @Test fun apiBackedBlocksUseTheirDetailedActionWhileInlineControlStepsRemainVisible() {
        fun step(title: String) = JsonObject().apply {
            addProperty("version", 1); addProperty("kind", "step"); addProperty("phase", "enter")
            addProperty("receivedAtElapsedMs", 12); addProperty("flowId", "flow-1"); addProperty("nodeId", "node-1")
            add("visualLocation", JsonObject().apply {
                addProperty("line", 3); addProperty("flowName", "测试"); addProperty("blockTitle", title)
            })
        }.toString()
        assertNull(RuntimeScriptLog.observation(step("拆分文本")))
        assertNull(RuntimeScriptLog.observation(step("读取项目数据文件")))
        assertNotNull(RuntimeScriptLog.observation(step("变量计算")))
        assertNotNull(RuntimeScriptLog.observation(step("如果 / 否则如果 / 否则")))
    }

    @Test fun boundedSupplementaryUnicodeParametersAreNotSilentlyDiscarded() {
        val details = "\uD83D\uDD0D".repeat(200)
        val event = JsonObject().apply {
            addProperty("version", 1); addProperty("kind", "action"); addProperty("receivedAtElapsedMs", 10)
            addProperty("operation", "拆分文本"); addProperty("status", "completed"); addProperty("details", details)
        }
        assertTrue(RuntimeScriptLog.observation(event.toString())!!.contains(details))
        event.addProperty("details", "x".repeat(513))
        assertNull(RuntimeScriptLog.observation(event.toString()))
    }
}
