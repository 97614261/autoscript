package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogEventCodec
import org.junit.Assert.*
import org.junit.Test

class RuntimeLogLocationTest {
    @Test fun terminalFailuresAreLocatedButCancelledAndScriptTextAreNot() {
        val events = """
            {"version":1,"kind":"task","flowId":"main","nodeId":"node-1","status":"failed"}
            {"version":1,"kind":"task","flowId":"main","nodeId":"node-2","status":"cancelled"}
            {"version":1,"kind":"script","flowId":"main","nodeId":"node-3","status":"failed"}
            LUA_RUNTIME_ERROR main node-4
        """.trimIndent()
        assertEquals(listOf(RuntimeLogLocation("main", "node-1")), RuntimeLogLocation.failures(events))
        assertNull(RuntimeLogLocation.from(mapOf("flowId" to "anonymous:01234", "nodeId" to "node")))
        assertNull(RuntimeLogLocation.from(mapOf("flowId" to "../private", "nodeId" to "node")))
    }
    @Test fun compactConditionsAndMalformedRecordsAreHandledIndependently() {
        val encoded = RuntimeLogEventCodec.Encoder().encode("""{"version":1,"kind":"vision","flowId":"main","nodeId":"node-1","algorithm":"rust-rgba-first","status":"timeout","elapsedUs":30}""")
        assertEquals(listOf(RuntimeLogLocation("main", "node-1")),
            RuntimeLogLocation.failures("broken\n${encoded.condition}\n${encoded.sample}"))
        assertTrue(RuntimeLogLocation.failures(encoded.sample).isEmpty())
    }
    @Test fun recentLocationsAreBoundedAndDeduplicated() {
        val events = (1..30).joinToString("\n") { """{"version":1,"kind":"task","flowId":"main","nodeId":"node-$it","status":"failed"}""" }
        val locations = RuntimeLogLocation.failures(events)
        assertEquals(16, locations.size)
        assertEquals("node-30", locations.first().nodeId)
    }
    @Test fun failureFilteringDoesNotTurnNotFoundOrCancellationIntoErrors() {
        assertTrue(editorConsoleLineIsFailure("123ms · 截图 超时 · 5000ms · node"))
        assertTrue(editorConsoleLineIsFailure("Runner错误码 · ROOT_STOP_CLEANUP_FAILED"))
        assertTrue(editorConsoleLineIsFailure("""{"kind":"task","status":"failed"}"""))
        assertFalse(editorConsoleLineIsFailure("123ms · 找图 未找到 · 10ms · node"))
        assertFalse(editorConsoleLineIsFailure("""{"kind":"task","status":"cancelled"}"""))
    }
    @Test fun copySelectionIsBoundedInUtf8WithoutSilentTruncation() {
        assertEquals("甲\n乙", runtimeLogCopyText(listOf("甲", "乙")))
        assertThrows(IllegalArgumentException::class.java) { runtimeLogCopyText(listOf("a".repeat(128 * 1024 + 1))) }
        assertThrows(IllegalArgumentException::class.java) { runtimeLogCopyText(listOf("汉".repeat(50_000))) }
    }
}
