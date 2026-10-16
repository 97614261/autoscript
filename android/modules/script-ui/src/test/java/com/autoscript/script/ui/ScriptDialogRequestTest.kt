package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptDialogRequestTest {
    @Test fun parsesExactChoicesAndSavedPageIdentity() {
        val confirm = ScriptDialogRequest.parse("window", "dialogConfirm", """{"title":"确认","message":"继续？","confirmText":"确定","cancelText":"取消","timeoutMs":60000}""")
        assertNull(confirm.pageId); assertEquals("继续？", confirm.message); assertFalse(confirm.blockOutside)
        val layout = ScriptDialogRequest.parse("settings", "dialogShow", """{"blockOutside":true,"timeoutMs":1000}""")
        assertEquals("settings", layout.pageId); assertTrue(layout.blockOutside); assertEquals(1000L, layout.timeoutMs)
    }
    @Test fun rejectsPathsWrongTypesUnknownFieldsAndUnboundedWaits() {
        for ((id, source) in listOf("../main" to """{"blockOutside":true,"timeoutMs":1000}""",
            "main" to """{"blockOutside":"true","timeoutMs":1000}""",
            "main" to """{"blockOutside":true,"timeoutMs":999}""",
            "main" to """{"blockOutside":true,"timeoutMs":1800001}""",
            "main" to """{"blockOutside":true,"timeoutMs":1000,"file":"secret"}""")) {
            assertTrue(runCatching { ScriptDialogRequest.parse(id,"dialogShow",source) }.isFailure)
        }
        val json = """{"title":"T","message":"${"汉".repeat(1366)}","confirmText":"OK","cancelText":"Cancel","timeoutMs":1000}"""
        assertTrue(runCatching { ScriptDialogRequest.parse("window","dialogConfirm",json) }.isFailure)
    }
    @Test fun choicesCompleteOnceAndStopDiscardsLatePositiveAnswers() {
        val results = mutableListOf<String>()
        val confirm = ScriptDialogCompletion(true) { results += it }
        confirm.finish("dismiss"); confirm.finish("confirm")
        assertEquals(listOf("dismiss"),results)
        val stopped = ScriptDialogCompletion(true) { results += it }
        stopped.cancel(); stopped.finish("confirm")
        assertEquals(listOf("dismiss"),results)
        val layout = ScriptDialogCompletion(false) { results += it }
        assertTrue(runCatching { layout.finish("confirm") }.isFailure)
        layout.finish("close"); layout.finish("timeout")
        assertEquals(listOf("dismiss","close"),results)
    }
}
