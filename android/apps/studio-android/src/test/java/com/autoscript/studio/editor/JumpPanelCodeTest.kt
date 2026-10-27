package com.autoscript.studio

import com.autoscript.project.store.ProjectFlow
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpPanelCodeTest {
    @Test
    fun fixedWaitCarriesExactBlockKindAndMillisecondsWithoutFuzzySearch() {
        val contract = requireNotNull(BlockCatalog.find("task.sleep"))
        val hint = requireNotNull(JumpPanelCode.waitSnippet(173))
        assertEquals("task.sleep", FunctionCatalog.blockKindOf(hint))
        val args = legacyDockBlockArguments(contract, hint, JsonObject().apply { addProperty("milliseconds", 0) })
        assertEquals(173, args["milliseconds"].asInt)
        assertNull(JumpPanelCode.waitSnippet(-1))
        val oldVariable = legacyDockBlockArguments(contract,
            "${FunctionCatalog.BLOCK_HINT_PREFIX}task.sleep\nvariable:delay", JsonObject())
        assertEquals("delay", oldVariable["millisecondsVariable"].asString)
    }

    @Test
    fun returnStatusCanSwitchWithoutChangingBlockKind() {
        assertTrue(JumpPanelCode.canSelectAction("flow.status.return", 5, 6))
        assertTrue(JumpPanelCode.canSelectAction("flow.status.return", 6, 5))
        assertFalse(JumpPanelCode.canSelectAction("flow.status.return", 5, 2))
        assertFalse(JumpPanelCode.canSelectAction("flow.return", 2, 6))
        assertTrue(JumpPanelCode.canSelectAction(null, 0, 6))
    }

    @Test
    fun automaticLabelsAndTypedChannelValuesAreStable() {
        assertEquals("mark_3", JumpPanelCode.nextLabel(listOf("mark_1", "mark_2")))
        assertTrue(JumpPanelCode.validName("ready_2"))
        assertFalse(JumpPanelCode.validName("2ready"))
        assertNull(JumpPanelCode.fixedValue("null"))
        assertNull(JumpPanelCode.fixedValue("[1,2]"))
        assertEquals("123", JumpPanelCode.fixedValue("123")?.asString)
        assertTrue(JumpPanelCode.fixedValue("true")!!.asBoolean)
        assertEquals("123", JumpPanelCode.fixedValue("\"123\"")?.asString)

        val contract = requireNotNull(BlockCatalog.find("flow.argument.set"))
        val variableHint = requireNotNull(JumpPanelCode.setSnippet("flow.argument.set", 2, "ignored", "score"))
        val variableArgs = legacyDockBlockArguments(contract, variableHint,
            JsonObject().apply { addProperty("index", 1); addProperty("value", 0) })
        assertEquals(2, variableArgs.get("index").asInt)
        assertEquals("score", variableArgs.get("valueVariable").asString)
        assertFalse(variableArgs.has("value"))

        val fixedHint = requireNotNull(JumpPanelCode.setSnippet("flow.argument.set", 3, "\"007\"", null))
        val fixedArgs = legacyDockBlockArguments(contract, fixedHint, variableArgs)
        assertEquals("007", fixedArgs.get("value").asString)
        assertFalse(fixedArgs.has("valueVariable"))
        assertNull(JumpPanelCode.setSnippet("flow.argument.set", 100, "1", null))
        assertNull(JumpPanelCode.getSnippet("flow.return.get", 1, "invalid-name"))
    }

    @Test
    fun selectedCallTargetKeepsItsOwnRequiredDefaults() {
        val first = ProjectFlow("first", "flows/first.jsonl", "root")
        val chosen = ProjectFlow("chosen", "flows/chosen.jsonl", "root", params = listOf(
            JsonObject().apply {
                addProperty("name", "count")
                addProperty("type", "integer")
                addProperty("required", true)
            },
        ))
        val call = requireNotNull(BlockCatalog.find("flow.call"))
        val defaults = requireNotNull(initialBlockArguments(call, listOf(first, chosen), emptyList(),
            currentFlowId = "main", preferredFlowId = "chosen"))
        assertEquals("chosen", defaults.get("targetFlowId").asString)
        assertEquals(0, defaults.getAsJsonObject("arguments").get("count").asInt)
        val snippet = requireNotNull(JumpPanelCode.callSnippet("chosen"))
        assertEquals("flow.call", FunctionCatalog.blockKindOf(snippet))
        val args = legacyDockBlockArguments(call, snippet, defaults)
        assertEquals("chosen", args.get("targetFlowId").asString)
        assertNotNull(args.getAsJsonObject("arguments").get("count"))
        assertNull(initialBlockArguments(call, listOf(first, chosen), emptyList(),
            currentFlowId = "main", preferredFlowId = "missing"))
    }

    @Test
    fun insertionChecksLabelsAndLoopContextBeforeWriting() {
        var id = 0
        val editor = VisualEditorState.create("", "root") { "jump-${++id}" }
        val label = requireNotNull(BlockCatalog.find("control.label"))
        val ready = JsonObject().apply { addProperty("name", "ready") }
        assertNotNull(editor.insertBlock(label, ready))
        assertEquals("当前插件已有同名标记：ready",
            visualJumpInsertionError("control.label", ready, editor, null, EditorInsertPosition.BELOW))
        assertNull(visualJumpInsertionError("control.goto", ready, editor, null, EditorInsertPosition.BELOW))
        val missing = JsonObject().apply { addProperty("name", "missing") }
        assertEquals("请先在当前插件放置标记：missing",
            visualJumpInsertionError("control.goto", missing, editor, null, EditorInsertPosition.BELOW))
        assertEquals("跳出/结束本次循环只能加入循环体",
            visualJumpInsertionError("control.break", JsonObject(), editor, null, EditorInsertPosition.LIST_BOTTOM))
    }

    @Test
    fun branchCallsKeepTypedArgumentsAndBothChildAreasWhenModified() {
        var id = 0
        val editor = VisualEditorState.create("", "root") { "branch-${++id}" }
        val contract = requireNotNull(BlockCatalog.find("flow.call.branch"))
        val hint = requireNotNull(JumpPanelCode.callSnippet("worker", branches = true))
        assertEquals("flow.call.branch", FunctionCatalog.blockKindOf(hint))
        assertEquals("worker", JumpPanelCode.callTargetId(hint))
        val defaults = JsonObject().apply {
            addProperty("targetFlowId", "worker")
            add("arguments", JsonObject().apply { addProperty("count", 5) })
        }
        val args = legacyDockBlockArguments(contract, hint, defaults)
        assertEquals(5, args.getAsJsonObject("arguments")["count"].asInt)
        val inserted = requireNotNull(editor.insertBlock(contract, args))
        assertEquals(setOf("success", "failure"), editor.childBlockNames(inserted).toSet())
        val modified = requireNotNull(editor.nodeArguments(inserted)).apply {
            addProperty("targetFlowId", "next")
            add("arguments", JsonObject().apply { addProperty("count", 9) })
        }
        assertTrue(editor.updateArguments(inserted, modified))
        modified.addProperty("targetFlowId", "must-not-alias")
        val saved = requireNotNull(editor.nodeArguments(inserted))
        assertEquals("next", saved["targetFlowId"].asString)
        assertEquals(9, saved.getAsJsonObject("arguments")["count"].asInt)
        assertEquals(setOf("success", "failure"), editor.childBlockNames(inserted).toSet())
        for (success in listOf(true, false)) {
            val status = requireNotNull(BlockCatalog.find("flow.status.return"))
            val updated = legacyDockBlockArguments(status, JumpPanelCode.statusSnippet(success), JsonObject())
            assertEquals(success, updated["success"].asBoolean)
        }
        assertNotNull(visualJumpInsertionError("control.continue", JsonObject(), editor, null, EditorInsertPosition.LIST_BOTTOM))
    }
}
