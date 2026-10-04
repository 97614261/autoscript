package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOutputCodeTest {

    @Test
    fun dynamicMessagesKeepExpressionsChannelsAndSavedEdits() {
        var id = 0
        val expression = "\"进度：\" .. text(count)"
        val contract = requireNotNull(BlockCatalog.find("task.message"))
        for ((mode, channel) in listOf(0 to "output", 1 to "popup", 2 to "console")) {
            val snippet = requireNotNull(DebugOutputCode.expressionSnippet(mode, expression, true))
            assertEquals("task.message", FunctionCatalog.blockKindOf(snippet))
            val args = legacyDockBlockArguments(contract, snippet, JsonObject())
            assertEquals(expression, args["expression"].asString)
            assertEquals(channel, args["channel"].asString)
            assertEquals(mode, DebugOutputCode.expressionOutputMode(args))
            val editor = VisualEditorState.create("", "root") { "message-${++id}" }
            val node = requireNotNull(editor.insertBlock(contract, args))
            val restored = VisualEditorState.create(editor.currentSource, "root") { "restore-${++id}" }
            assertEquals(args, restored.nodeArguments(node))
            val changed = args.deepCopy().apply { addProperty("expression", "\"next\"") }
            assertTrue(restored.updateArguments(node, changed))
            assertEquals("\"next\"", restored.nodeArguments(node)!!["expression"].asString)
        }
        assertNull(DebugOutputCode.expressionSnippet(1, expression, false))
        assertNull(DebugOutputCode.expressionSnippet(3, expression, true))
        assertNull(DebugOutputCode.expressionSnippet(1, "中".repeat(342), true))
        assertEquals("\"进度\" .. text(count)", DebugOutputCode.appendExpressionVariable("\"进度\"", "count"))
        assertEquals("\"进度\" ..text(count)", DebugOutputCode.appendExpressionVariable("\"进度\" .. ", "count"))
        assertNull(DebugOutputCode.appendExpressionVariable("x".repeat(1024), "count"))
        assertNull(DebugOutputCode.appendExpressionVariable("", "x);shell"))
    }
    @Test
    fun visualOutputHasFourDistinctTypedKindsAndPreservesMessage() {
        val cases = listOf(
            DebugOutputCode.RUN_PROMPT to "task.runprompt",
            DebugOutputCode.TOAST to "task.prompt",
            DebugOutputCode.LOG to "task.log",
            DebugOutputCode.COMMENT to "task.comment",
        )
        cases.forEach { (mode, kind) ->
            val snippet = requireNotNull(DebugOutputCode.snippet(mode, "第一行\n第二行", null, true))
            assertEquals(kind, FunctionCatalog.blockKindOf(snippet))
            val args = legacyDockBlockArguments(
                requireNotNull(BlockCatalog.find(kind)), snippet, JsonObject(),
            )
            assertEquals("第一行\n第二行", args.get("message").asString)
        }
    }

    @Test
    fun visualVariableOutputKeepsVariableBindingAndText() {
        val snippet = requireNotNull(DebugOutputCode.snippet(DebugOutputCode.RUN_PROMPT, "处理进度", "status", true))
        val args = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("task.runprompt")), snippet, JsonObject(),
        )
        assertEquals("status", args.get("valueVariable").asString)
        assertEquals("处理进度", args.get("message").asString)
    }

    @Test
    fun luaChannelsAreSeparateAndInvalidInputIsRejected() {
        assertEquals("Prompt.show(\"运行中\")\n", DebugOutputCode.snippet(0, "运行中", null, false))
        assertEquals("Prompt.toast(tostring(status))\n", DebugOutputCode.snippet(1, "", "status", false))
        assertEquals("Log.info(\"a\\n\\\"b\")\n", DebugOutputCode.snippet(2, "a\n\"b", null, false))
        assertFalse(DebugOutputCode.error(0, "", null).isNullOrEmpty())
        assertFalse(DebugOutputCode.error(0, "ok", "not-valid").isNullOrEmpty())
        assertFalse(DebugOutputCode.error(0, "中".repeat(683), null).isNullOrEmpty())
        assertNull(DebugOutputCode.error(0, "", "status"))
        assertTrue(DebugOutputCode.snippet(3, "说明", null, false)!!.startsWith("-- "))
    }
}
