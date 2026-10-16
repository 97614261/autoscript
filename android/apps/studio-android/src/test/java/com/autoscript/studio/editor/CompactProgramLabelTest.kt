package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CompactProgramLabelTest {
    private fun label(kind: String, json: String): String =
        compactProgramLabel(BlockCatalog.find(kind), JsonParser.parseString(json).asJsonObject, kind)

    @Test fun coordinatesAndSwipeShowEndpointsAndDuration() {
        assertTrue(label("input.tap", """{"x":320,"y":640}""").endsWith("· (320, 640)"))
        assertTrue(label("input.swipe", """{"x1":1,"y1":2,"x2":3,"y2":4,"durationMs":500}""")
            .endsWith("· (1, 2) → (3, 4) · 500ms"))
    }

    @Test fun assignmentsDistinguishStringFromNumber() {
        assertTrue(label("variable.set", """{"name":"count","value":3}""").endsWith("count = 3"))
        assertTrue(label("variable.set", """{"name":"count","value":"3"}""").endsWith("count = \"3\""))
        assertTrue(label("variable.copy", """{"name":"result","sourceName":"count"}""").endsWith("result ← count"))
    }

    @Test fun dynamicValuesDoNotShowInactiveFixedValues() {
        val repeat = label("control.repeat", """{"times":0,"timesVariable":"count"}""")
        assertTrue(repeat.endsWith("变量 count 次"))
        assertFalse(repeat.contains("0 次"))
        assertTrue(label("task.sleep", """{"milliseconds":0,"millisecondsVariable":"delay"}""")
            .endsWith("变量 delayms"))
    }

    @Test fun compoundConditionNeverShowsInactiveComparison() {
        val summary = label("control.if", """{"variable":"unused","value":false,"condition":{"type":"all","conditions":[]}}""")
        assertTrue(summary.endsWith("复合条件"))
        assertFalse(summary.contains("unused"))
        assertTrue(label("control.if", """{"variable":"count","operator":"greaterThan","value":0,"valueVariable":"limit"}""")
            .endsWith("count > 变量 limit"))
    }

    @Test fun visionShowsTemplatesInsteadOfFrameInternals() {
        val summary = label("vision.findallimages", """{"frameVariable":"frame","imagePaths":["images/a.png","images/b.png"],"resultVariable":"matches"}""")
        assertTrue(summary.endsWith("2 张模板 · → matches"))
        assertFalse(summary.contains("frame"))
    }

    @Test fun summaryIsBoundedSingleLineAndDoesNotMutateData() {
        val args = JsonObject().apply { addProperty("message", "hello\n\t" + "x".repeat(5000)) }
        val original = args.toString()
        val summary = compactProgramLabel(BlockCatalog.find("task.comment"), args, "task.comment")
        assertFalse(summary.contains('\n'))
        assertFalse(summary.contains('\t'))
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length < 120)
        assertEquals(original, args.toString())
    }

    @Test fun missingAndStructuredValuesRemainExplicit() {
        assertTrue(label("input.tap", "{}").endsWith("(?, ?)"))
        assertTrue(label("variable.set", """{"name":"data","value":{}}""").endsWith("结构化值"))
        assertEquals("unknown.kind", compactProgramLabel(null, null, "unknown.kind"))
        assertEquals(BlockCatalog.find("input.tap")!!.title, compactProgramLabel(BlockCatalog.find("input.tap"), null, "input.tap"))
    }
}
