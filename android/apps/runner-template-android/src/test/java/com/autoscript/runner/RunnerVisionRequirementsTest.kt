package com.autoscript.runner

import org.junit.Assert.*
import org.junit.Test

class RunnerVisionRequirementsTest {
    @Test fun actualSourceMapReaderAcceptsCompiledMetadataAndHistoricalAbsence() {
        val base = """{"schemaVersion":1,"generationId":"gen-${"a".repeat(32)}","entries":[{"flowId":"main","nodeId":"node-1","luaStartLine":2,"luaEndLine":4}]}"""
        val old = parseRunnerSourceMap(base.toByteArray())
        assertNull(old.requiredVisionComponents); assertEquals(1, old.entries.size)
        val current = parseRunnerSourceMap((base.dropLast(1) + """, "visionRequirements":{"version":1,"components":["onnx"]}}""").toByteArray())
        assertEquals(setOf("onnx"), current.requiredVisionComponents)
        assertEquals(old.entries, current.entries); assertEquals(old.generationId, current.generationId)
        for (invalid in listOf(base.replace("schemaVersion\":1", "schemaVersion\":2"),
            base.replace("luaStartLine\":2", "luaStartLine\":2.0"), base.replace("luaEndLine\":4", "luaEndLine\":1"),
            base.replace("node-1", ""), base.dropLast(1) + ",\"unknown\":true}"))
            assertThrows(IllegalArgumentException::class.java) { parseRunnerSourceMap(invalid.toByteArray()) }
    }
    private fun read(body: String) = runnerVisionRequirements(body.toByteArray())
    @Test fun unknownAndKnownEmptyDoNotCollapse() {
        assertNull(read("{}"))
        assertEquals(emptySet<String>(), read("""{"visionRequirements":{"version":1,"components":[]}}"""))
        assertEquals(setOf("opencv", "onnx"), read("""{"visionRequirements":{"version":1,"components":["opencv","onnx"]}}"""))
    }
    @Test fun rejectsMalformedAndNoncanonicalRequirements() {
        for (value in listOf("null", "{}", "[]",
            """{"version":1.0,"components":[]}""", """{"version":"1","components":[]}""",
            """{"version":2,"components":[]}""", """{"version":1,"components":["onnx","opencv"]}""",
            """{"version":1,"components":["opencv","opencv"]}""", """{"version":1,"components":["external"]}""",
            """{"version":1,"components":[1]}""", """{"version":1,"components":[],"path":"untrusted"}"""))
            assertThrows(IllegalArgumentException::class.java) { read("{\"visionRequirements\":$value}") }
    }
}
