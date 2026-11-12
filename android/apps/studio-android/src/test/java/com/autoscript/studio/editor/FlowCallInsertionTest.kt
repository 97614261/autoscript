package com.autoscript.studio

import com.autoscript.project.store.ProjectFlow
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class FlowCallInsertionTest {
    private fun parameter(name: String, type: String, required: Boolean = true) = JsonObject().apply {
        addProperty("name", name); addProperty("type", type); addProperty("required", required)
    }
    private val target = ProjectFlow("chosen", "flows/chosen.jsonl", "root", params = listOf(
        parameter("count", "integer"), parameter("ratio", "number"), parameter("enabled", "boolean"),
        parameter("message", "string"), parameter("optional", "string", false),
    ))
    private val values = mapOf("count" to "19", "ratio" to "1.25", "enabled" to "false", "message" to "hello\n\"world\"")

    @Test fun switchingCallTargetsKeepsSeparateDraftsAndOriginalTypedValues() {
        val other = target.copy(flowId = "other")
        val arguments = JsonObject().apply {
            addProperty("targetFlowId", target.flowId)
            add("arguments", requireNotNull(parseFlowCallArguments(target, values).first))
        }
        var draft = PluginCallDraft.create(listOf(other, target), arguments)
        assertEquals(target.flowId, draft.targetId)
        assertEquals(values["message"], draft.inputs["message"])
        draft = draft.update("count", "29").select(other).update("count", "7").select(target)
        assertEquals("29", draft.inputs["count"])
        assertEquals("7", draft.select(other).inputs["count"])
        assertEquals(19, arguments.getAsJsonObject("arguments")["count"].asInt)
        assertEquals(29, requireNotNull(parseFlowCallArguments(target, draft.inputs).first)["count"].asInt)
    }

    @Test fun missingOriginalCallTargetIsNotSilentlyReplaced() {
        val arguments = JsonObject().apply { addProperty("targetFlowId", "deleted") }
        val draft = PluginCallDraft.create(listOf(target), arguments)
        assertEquals("deleted", draft.targetId)
        assertTrue(draft.inputs.isEmpty())
        assertEquals(target.flowId, draft.select(target).targetId)
    }

    @Test fun selectedCallNeedsNoSecondTargetConfigurationButBareCatalogCallsDo() {
        for (kind in listOf("flow.call", "flow.call.branch")) {
            assertTrue(JumpPanelCode.callNeedsConfiguration(kind, "${FunctionCatalog.BLOCK_HINT_PREFIX}$kind\n"))
            val hint = requireNotNull(JumpPanelCode.configuredCallSnippet(target, values, kind.endsWith("branch")).first)
            assertFalse(JumpPanelCode.callNeedsConfiguration(kind, hint))
            assertEquals("chosen", JumpPanelCode.callTargetId(hint))
        }
        assertFalse(JumpPanelCode.callNeedsConfiguration("task.sleep", "unrelated"))
    }

    @Test fun allTypedParametersAndSelectedTargetSurviveTheDockAdapter() {
        val first = ProjectFlow("first", "flows/first.jsonl", "root")
        for (branches in listOf(false, true)) {
            val (hint, problem) = JumpPanelCode.configuredCallSnippet(target, values, branches)
            assertNull(problem)
            val contract = requireNotNull(BlockCatalog.find(if (branches) "flow.call.branch" else "flow.call"))
            val defaults = requireNotNull(initialBlockArguments(contract, listOf(first, target), emptyList(), "main", preferredFlowId = "chosen"))
            val args = legacyDockBlockArguments(contract, requireNotNull(hint), defaults)
            assertEquals("chosen", args["targetFlowId"].asString)
            val passed = args.getAsJsonObject("arguments")
            assertEquals(19, passed["count"].asInt)
            assertEquals(1.25, passed["ratio"].asDouble, 0.0)
            assertFalse(passed["enabled"].asBoolean)
            assertEquals(values["message"], passed["message"].asString)
            assertFalse(passed.has("optional"))
            assertEquals(0, defaults.getAsJsonObject("arguments")["count"].asInt)
        }
    }

    @Test fun invalidOrMissingTargetParametersDoNotProduceAnInsertableHint() {
        assertNull(JumpPanelCode.configuredCallSnippet(null, values, false).first)
        for (bad in listOf(values + ("count" to "1.5"), values + ("ratio" to "NaN"),
            values + ("enabled" to "yes"), values - "count")) {
            val (hint, error) = JumpPanelCode.configuredCallSnippet(target, bad, false)
            assertNull(hint); assertNotNull(error)
        }
    }

    @Test fun configuredCallCommitsOnceAndOneUndoRemovesBothBranches() {
        val editor = VisualEditorState.create("", "root")
        val before = editor.currentSource
        val context = editorInsertionContext("project", "main", editor)
        val contract = requireNotNull(BlockCatalog.find("flow.call.branch"))
        val hint = requireNotNull(JumpPanelCode.configuredCallSnippet(target, values, true).first)
        val defaults = requireNotNull(initialBlockArguments(contract, listOf(target), emptyList(), "main"))
        val arguments = legacyDockBlockArguments(contract, hint, defaults)
        assertNull(applyConfiguredEditorInsertion(context, "project", "main", editor, contract, arguments,
            EditorInsertPosition.LIST_BOTTOM, null, contract.requiredCapabilities.toSet(), true))
        val id = requireNotNull(editor.selectedNodeId)
        assertEquals(1, editor.rows.size)
        assertEquals(setOf("success", "failure"), editor.childBlockNames(id).toSet())
        assertEquals(19, requireNotNull(editor.nodeArguments(id)).getAsJsonObject("arguments")["count"].asInt)
        assertNotNull(applyConfiguredEditorInsertion(context, "project", "main", editor, contract, arguments,
            EditorInsertPosition.LIST_BOTTOM, null, contract.requiredCapabilities.toSet(), true))
        assertEquals(1, editor.rows.size)
        assertTrue(editor.undo()); assertEquals(before, editor.currentSource)
    }

    @Test fun parameterlessCallsFinishWithAnExplicitEmptyArgumentMap() {
        val worker = ProjectFlow("worker", "flows/worker.jsonl", "root")
        val (hint, error) = JumpPanelCode.configuredCallSnippet(worker, emptyMap(), false)
        assertNull(error)
        val contract = requireNotNull(BlockCatalog.find("flow.call"))
        val arguments = legacyDockBlockArguments(contract, requireNotNull(hint), JsonObject())
        assertEquals("worker", arguments["targetFlowId"].asString)
        assertEquals(0, arguments.getAsJsonObject("arguments").size())
        assertFalse(JumpPanelCode.callNeedsConfiguration(contract.kind, hint))
    }
}
