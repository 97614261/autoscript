package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeScriptLog
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class RecognitionLogVisibilityTest {
    private fun event(opcode: Int, operation: String, status: String, title: String = "区域找图"): String =
        JsonObject().apply {
            addProperty("version", 1); addProperty("kind", "action"); addProperty("opcode", opcode)
            addProperty("operation", operation); addProperty("status", status)
            addProperty("details", "区域 (10, 20) → (210, 120)")
            addProperty("receivedAtElapsedMs", 120)
            addProperty("flowId", "flow-1"); addProperty("nodeId", "node-3")
            add("visualLocation", JsonObject().apply {
                addProperty("flowName", "主任务"); addProperty("line", 3); addProperty("blockTitle", title)
            })
        }.toString()

    @Test fun foundMissAndActualClickAppearInBothLiveAndHistoryScriptLogs() {
        val events = listOf(event(5107, "查找图片", "found"), event(5107, "查找图片", "not_found"),
            event(4006, "点击", "completed"))
        val live = events.map { requireNotNull(RuntimeScriptLog.observation(it)) }
        assertEquals(live, filterRuntimeConsole(live, "", false, RuntimeConsoleChannel.ACTIVITY))
        assertEquals(live, RuntimeScriptLog.history("", events.joinToString("\n")))
        assertTrue(live[0].contains("找到")); assertTrue(live[1].contains("未找到"))
        assertTrue(live[2].contains("点击")); assertTrue(live[2].contains("完成"))
        live.forEach { assertTrue(it.contains("第 3 行")); assertTrue(it.contains("插件 主任务")) }
    }

    @Test fun recognitionHelpersDoNotBuryTheSearchButFailuresAndExplicitBlocksRemain() {
        for (opcode in listOf(5001, 5002, 5003, 5115)) {
            assertNull(RuntimeScriptLog.observation(event(opcode, "内部处理", "completed")))
            assertNotNull(RuntimeScriptLog.observation(event(opcode, "内部处理", "error")))
            assertNotNull(RuntimeScriptLog.observation(event(opcode, "读取结果", "completed", "读取找图结果")))
        }
    }
}
