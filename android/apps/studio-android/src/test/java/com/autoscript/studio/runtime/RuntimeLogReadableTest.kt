package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogWriter
import com.autoscript.runtime.api.RuntimeScriptLog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeLogReadableTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun event(failed: Boolean = false): JsonObject = JsonParser.parseString(
        """{"version":1,"kind":"trace","phase":"enter","flowId":"flow-1","nodeId":"node-12","receivedAtElapsedMs":22,
            "visualLocation":{"line":12,"flowName":"放技能","blockTitle":"点击"},"source":"private source","value":"private value"}"""
    ).asJsonObject.apply { if (failed) { addProperty("kind", "task"); addProperty("status", "failed") } }

    @Test fun executionShowsNameLineAndOperationWhileRawDetailsRetainIdentity() {
        val raw = RuntimeScriptLog.observation(event().toString())!!
        val display = runtimeLogLinePresentation(raw)
        assertEquals("插件 放技能 · 第 12 行 · 点击", display.message)
        assertEquals("积木", display.label)
        assertFalse(display.failure)
        assertFalse(display.message.contains("node-12"))
        assertTrue(raw.contains("[定位:flow-1/node-12]"))
        assertFalse(raw.contains("private"))
        assertEquals(listOf(raw), filterRuntimeConsole(listOf(raw), "放技能", false))
        assertEquals(listOf(raw), filterRuntimeConsole(listOf(raw), "node-12", false))
    }

    @Test fun failureShowsReadableLocationAndPreservesStructuredNavigation() {
        val payload = event(failed = true).toString()
        val raw = RuntimeScriptLog.observation(payload)!!
        val display = runtimeLogLinePresentation(raw)
        assertEquals("插件 放技能 · 第 12 行 · 点击 · 执行失败", display.message)
        assertEquals("错误", display.label)
        assertTrue(display.failure)
        assertEquals(listOf(RuntimeLogLocation("flow-1", "node-12")), RuntimeLogLocation.failures(payload))
        assertEquals(listOf(raw), filterRuntimeConsole(listOf(raw), "", true))
    }

    @Test fun pluginNamesAndRowsStaySeparateForTheSameNodeIdentity() {
        val first = event()
        val second = event().apply {
            addProperty("flowId", "flow-2")
            getAsJsonObject("visualLocation").apply {
                addProperty("flowName", "普攻"); addProperty("line", 7); addProperty("blockTitle", "长按")
            }
        }
        val history = RuntimeScriptLog.history("", "$first\n$second")
        assertEquals(listOf("插件 放技能 · 第 12 行 · 点击", "插件 普攻 · 第 7 行 · 长按"),
            history.map { runtimeLogLinePresentation(it).message })
    }

    @Test fun malformedOrMissingLocationKeepsFailureAndDoesNotInventAName() {
        val badLocations = listOf(
            "null", "{}", "42", "[]",
            """{"line":0,"flowName":"bad","blockTitle":"bad"}""",
            """{"line":-1,"flowName":"bad","blockTitle":"bad"}""",
            """{"line":1.5,"flowName":"bad","blockTitle":"bad"}""",
            """{"line":"12","flowName":"bad","blockTitle":"bad"}""",
            """{"line":4294967296,"flowName":"bad","blockTitle":"bad"}""",
            """{"line":12,"flowName":true,"blockTitle":"bad"}""",
            """{"line":12,"flowName":"bad\nspoof","blockTitle":"bad"}""",
            JsonObject().apply {
                addProperty("line", 12); addProperty("flowName", "名".repeat(171)); addProperty("blockTitle", "bad")
            }.toString(),
        )
        badLocations.forEach { location ->
            val payload = event(failed = true).apply { add("visualLocation", JsonParser.parseString(location)) }
            val raw = RuntimeScriptLog.observation(payload.toString())!!
            assertEquals("22ms · 脚本执行/ERROR: 插件 flow-1 · 积木 node-12 · 执行失败", raw)
            assertEquals("积木执行失败（此记录未保存名称和行号）", runtimeLogLinePresentation(raw).message)
            assertTrue(editorConsoleLineIsFailure(raw))
        }
        val legacy = event().apply { remove("visualLocation") }
        assertEquals("执行到一条积木（此记录未保存名称和行号）",
            runtimeLogLinePresentation(RuntimeScriptLog.observation(legacy.toString())!!).message)
    }

    @Test fun scriptOutputContainingLocationSyntaxRemainsUntouched() {
        val messages = listOf("插件 flow-1 · 积木 node-12", "插件 放技能 · 第 12 行 · 点击 [定位:flow-1/node-12]",
            "插件 flow-1 · 积木 node-12 · 执行失败")
        messages.forEach { message ->
            assertEquals(message, runtimeLogLinePresentation("22ms · 脚本/INFO: $message").message)
            assertEquals(message, runtimeLogLinePresentation("22ms · 脚本/ERROR: $message").message)
        }
    }

    @Test fun longNamesLeaveRoomForIdentityAndFailureInTheBoundedLiveRecord() {
        val payload = event(failed = true).apply {
            addProperty("flowId", "f".repeat(128)); addProperty("nodeId", "n".repeat(128))
            addProperty("receivedAtElapsedMs", Long.MAX_VALUE)
            getAsJsonObject("visualLocation").apply {
                addProperty("flowName", "a".repeat(512)); addProperty("blockTitle", "b".repeat(512))
                addProperty("line", 4_294_967_295L)
            }
        }
        val raw = RuntimeScriptLog.observation(payload.toString())!!
        assertTrue(raw.length.toString(), raw.length <= 512)
        assertTrue(raw.endsWith(" · 执行失败"))
        assertTrue(raw.contains("[定位:"))
        assertFalse(runtimeLogLinePresentation(raw).message.contains("[定位:"))
    }

    @Test fun readableNamesSurviveTheJournalWithoutConsultingLaterProjectContents() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(mapOf("projectId" to "qa"))!!
        val payload = event(failed = true)
        writer.append("script.log", "10ms · 脚本执行/START: 脚本开始运行")
        writer.append("events.jsonl", payload.toString())
        writer.end("failed"); writer.close()
        assertTrue(writer.awaitClosed(5000))
        payload.getAsJsonObject("visualLocation").addProperty("flowName", "之后改名")
        val snapshot = RuntimeLogFiles.snapshot(root, id, "script.log")
        val history = RuntimeScriptLog.history(snapshot.text, snapshot.events)
        assertEquals(2, history.size)
        assertEquals("插件 放技能 · 第 12 行 · 点击 · 执行失败", runtimeLogLinePresentation(history.last()).message)
        assertFalse(writer.failureCode, writer.failure)
        val exported = RuntimeLogFiles.export(root, snapshot.run)
        assertTrue(exported.contains("放技能"))
        assertTrue(exported.contains("node-12"))
        assertFalse(exported.contains("之后改名"))
    }
}
