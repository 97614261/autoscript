package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeScriptLog
import com.autoscript.runtime.api.RuntimeSystemLog
import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogWriter
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeLogRedesignTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun action(status: String = "found") = JsonParser.parseString("""{
        "version":1,"kind":"action","opcode":5101,"operation":"查找图片",
        "details":"区域 (10, 20) → (300, 400)","status":"$status","result":"(80, 90)",
        "flowId":"flow-2","nodeId":"node-8","receivedAtElapsedMs":123,
        "visualLocation":{"line":12,"flowName":"放技能","blockTitle":"查找图片"}
    }""").asJsonObject

    @Test fun actionShowsActualRangeResultPluginAndRowAndCanLocate() {
        val raw = RuntimeScriptLog.observation(action().toString())!!
        val display = runtimeLogLinePresentation(raw)
        assertTrue(display.message.contains("区域 (10, 20) → (300, 400)"))
        assertTrue(display.message.contains("找到 · (80, 90)"))
        assertTrue(display.message.contains("插件 放技能 · 第 12 行"))
        assertFalse(display.message.contains("[定位:"))
        assertEquals(RuntimeLogLocation("flow-2", "node-8"), RuntimeLogLocation.fromLine(raw))
        assertFalse(display.failure)
        assertEquals(listOf(raw), filterRuntimeConsole(listOf(raw), "", false, RuntimeConsoleChannel.ACTIVITY))
        assertTrue(filterRuntimeConsole(listOf(raw), "", false, RuntimeConsoleChannel.SYSTEM).isEmpty())
    }

    @Test fun missingIsNormalCancellationIsWarningAndErrorHasBothActionAndSystemContext() {
        val missed = action("not_found").apply { remove("result") }
        val miss = RuntimeScriptLog.observation(missed.toString())!!
        assertTrue(runtimeLogLinePresentation(miss).message.contains("未找到"))
        assertFalse(editorConsoleLineIsFailure(miss))
        val cancelled = RuntimeScriptLog.observation(action("cancelled").toString())!!
        assertTrue(runtimeLogLinePresentation(cancelled).warning)
        assertFalse(editorConsoleLineIsFailure(cancelled))
        val failed = RuntimeScriptLog.observation(action("error").apply { remove("result") }.toString())!!
        assertTrue(editorConsoleLineIsFailure(failed))
        assertEquals(listOf(failed), filterRuntimeConsole(listOf(failed), "", true, RuntimeConsoleChannel.SYSTEM))
        assertEquals(listOf(failed), filterRuntimeConsole(listOf(failed), "", false, RuntimeConsoleChannel.ACTIVITY))
    }

    @Test fun outputCannotImpersonateActionNavigationAndInvalidRecordsAreRejected() {
        assertNull(RuntimeLogLocation.fromLine("12ms · 脚本/INFO: [定位:flow-2/node-8]"))
        assertNull(RuntimeScriptLog.observation(action().apply { addProperty("details", "bad\nentry") }.toString()))
        assertNull(RuntimeScriptLog.observation(action().apply { addProperty("nodeId", "bad/path") }.toString()))
    }

    @Test fun historyKeepsRepeatedActionsAndScriptTextSeparateFromSystemLifecycle() {
        val event = action().toString()
        val lines = RuntimeScriptLog.history("1ms · 脚本执行/START: 脚本开始运行\n124ms · 脚本/INFO: value=190", "$event\n$event")
        val script = filterRuntimeConsole(lines, "", false, RuntimeConsoleChannel.ACTIVITY)
        assertEquals(3, script.size)
        assertEquals(script[0], script[1])
        assertEquals(1, filterRuntimeConsole(lines, "", false, RuntimeConsoleChannel.SYSTEM).size)
    }

    @Test fun systemJournalAppendsMultilineFailuresAndRotatesWithinItsBudget() {
        val files = temporary.newFolder()
        assertTrue(RuntimeSystemLog.record(files, "INFO", "开始运行", 1000))
        assertTrue(RuntimeSystemLog.record(files, "ERROR", "插件 放技能 · 第12行\nexpected stack", 2000))
        val records = RuntimeSystemLog.read(files)
        assertEquals(2, records.size)
        assertTrue(records[1].contains("expected stack"))
        assertTrue(editorConsoleLineIsFailure(records[1]))
        repeat(60) { assertTrue(RuntimeSystemLog.record(files, "INFO", "x".repeat(16000), 3000L + it)) }
        assertTrue(java.io.File(files, "system-logs").listFiles()!!.all { it.length() <= 262144 })
        assertTrue(RuntimeSystemLog.read(files).isNotEmpty())
    }

    @Test fun crashIsSavedWithLastKnownLocationThenDelegatedWithoutSwallowingAndroidHandler() {
        val files = temporary.newFolder()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var delegated = false
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
            RuntimeSystemLog.location("插件 测试 · 第3行 · 点击")
            RuntimeSystemLog.install(files, "qa:runner")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("qa"), IllegalStateException("expected-fixture"))
            assertTrue(delegated)
            val crash = RuntimeSystemLog.read(files).single()
            assertTrue(crash.contains("应用闪退"))
            assertTrue(crash.contains("第3行"))
            assertTrue(crash.contains("expected-fixture"))
        } finally {
            RuntimeSystemLog.location("")
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun diagnosticStackRemainsOneRecordInsteadOfManyUnlabelledRows() {
        val rows = runtimeLogDiagnosticRecords("10ms · 系统/ERROR: 插件 普攻 · 第9行\nstack traceback:\n  at test.lua:2\n11ms · Runner状态 · 已停止")
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("at test.lua:2"))
        assertTrue(editorConsoleLineIsFailure(rows[0]))
    }

    @Test fun actualJournalPreservesActionOrderResultsAndNavigationAcrossReopen() {
        val files = temporary.newFolder()
        val writer = RuntimeLogWriter(files)
        val id = writer.begin(mapOf("projectId" to "qa"))!!
        writer.append("events.jsonl", action("not_found").apply { remove("result") }.toString())
        writer.append("events.jsonl", action().apply { addProperty("receivedAtElapsedMs", 124) }.toString())
        writer.append("script.log", "125ms · 脚本/INFO: 结果=190")
        writer.append("diagnostic.log", "126ms · 系统/ERROR: 插件 放技能 · 第12行\nexpected diagnostic")
        writer.end("failed"); writer.close()
        assertTrue(writer.awaitClosed(5000))
        assertFalse(writer.failureCode, writer.failure)
        val snapshot = RuntimeLogFiles.snapshot(files, id, "script.log")
        val activity = filterRuntimeConsole(RuntimeScriptLog.history(snapshot.text, snapshot.events), "", false, RuntimeConsoleChannel.ACTIVITY)
        assertEquals(3, activity.size)
        assertTrue(runtimeLogLinePresentation(activity[0]).message.contains("未找到"))
        assertTrue(runtimeLogLinePresentation(activity[1]).message.contains("找到 · (80, 90)"))
        assertEquals(RuntimeLogLocation("flow-2", "node-8"), RuntimeLogLocation.fromLine(activity[1]))
        assertEquals("结果=190", runtimeLogLinePresentation(activity[2]).message)
        val system = runtimeLogDiagnosticRecords(RuntimeLogFiles.read(files, id, "diagnostic.log"))
        assertEquals(1, system.size)
        assertTrue(system.single().contains("expected diagnostic"))
    }
}
