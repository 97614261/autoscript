package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ConditionInsertionTest {
    private val contract = requireNotNull(BlockCatalog.find("control.if"))
    private fun comparison(value: Int = 10) = JsonObject().apply {
        addProperty("variable", "数量")
        addProperty("operator", "greaterOrEqual")
        addProperty("value", value)
    }
    private fun editor() = VisualEditorState.create("", "root").also {
        it.insertBlock(contract, comparison())
    }
    private fun append(editor: VisualEditorState, context: EditorPasteRequest, args: JsonObject = comparison(20),
        project: String = "project", flow: String = "main", mutable: Boolean = true) =
        applyConfiguredElseIfInsertion(context, project, flow, editor, args, emptySet(), mutable)

    @Test fun singleComparisonRoundTripKeepsChineseTextAndScalarTypes() {
        val args = comparison().apply {
            addProperty("operator", "equals")
            addProperty("value", "第一行\n\"匹配\"")
            addProperty("valueVariable", "目标")
        }
        val hint = configuredConditionHint(args)
        assertEquals(args, configuredConditionInsertion(hint)!!.arguments)
        assertEquals("control.if", FunctionCatalog.blockKindOf(hint))
        assertFalse(isConditionBranchInsertion(hint))
        assertEquals(args, legacyDockBlockArguments(contract, hint, comparison(-1)))
        val numeric = configuredConditionInsertion(configuredConditionHint(comparison()))!!.arguments
        assertTrue(numeric["value"].asJsonPrimitive.isNumber)
    }

    @Test fun compoundAndVariableComparisonRemainIntactForBothOperations() {
        val args = comparison().apply {
            add("condition", JsonObject().apply {
                addProperty("type", "all")
                add("conditions", JsonArray().apply {
                    add(conditionSeed("数量", "greaterThan", "0"))
                    add(conditionSeed("状态", "equals", "\"完成\"", "预期状态"))
                })
            })
        }
        for (branch in listOf(false, true)) {
            val hint = configuredConditionHint(args, branch)
            val parsed = requireNotNull(configuredConditionInsertion(hint))
            assertEquals(args, parsed.arguments)
            assertEquals(branch, parsed.elseIf)
            assertEquals(branch, isConditionBranchInsertion(hint))
        }
    }

    @Test fun malformedOrUnconfiguredHintsNeverBecomeConfiguredConditions() {
        for (hint in listOf("${FunctionCatalog.BLOCK_HINT_PREFIX}control.if\n", "--@autoscript-control:elseIf\n",
            CONDITION_CONFIG_HINT + "{}", CONDITION_CONFIG_HINT + "{\"operation\":\"elseIf\",\"arguments\":{}}",
            CONDITION_CONFIG_HINT + "x".repeat(18000))) assertNull(configuredConditionInsertion(hint))
        assertTrue(isConditionBranchInsertion("--@autoscript-control:else\n"))
    }

    @Test fun unknownFieldsWrongOperandsAndNestedBranchPayloadsAreRejected() {
        val invalid = listOf(
            comparison().apply { addProperty("variable", "") },
            comparison().apply { addProperty("operator", "unknown") },
            comparison().apply { add("value", JsonArray()) },
            comparison().apply { addProperty("valueVariable", "") },
            comparison().apply { add("elseIf", JsonArray()) },
            comparison().apply { add("condition", JsonObject()) },
        )
        val editor = editor(); val original = editor.currentSource
        for (args in invalid) {
            assertFalse(validConfiguredConditionArguments(args))
            assertNotNull(append(editor, editorInsertionContext("project", "main", editor), args))
            assertEquals(original, editor.currentSource)
        }
    }

    @Test fun configuringAndCancellingPositionDoesNotInsertAnything() {
        val editor = editor(); val original = editor.currentSource; val primary = editor.selectedNodeId
        var completed = false
        val completion = EditorInsertionCompletion { completed = true }
        val hint = configuredConditionHint(comparison(30))
        val ticket = EditorPendingSnippet(hint, completion::committed, "main", original, primary, editor.selectedNodeIds)
        assertTrue(ticket.matches("main", editor.currentSource, primary, editor.selectedNodeIds))
        assertEquals(comparison(30), configuredConditionInsertion(ticket.snippet)!!.arguments)
        assertFalse(completed)
        assertEquals(original, editor.currentSource)
        assertEquals(primary, editor.selectedNodeId)
        assertTrue(editor.undo())
        assertEquals("", editor.currentSource)
    }

    @Test fun ifCommitsConfiguredArgumentsOnceAfterPositionIsChosen() {
        val editor = editor(); val original = editor.currentSource
        val args = configuredConditionInsertion(configuredConditionHint(comparison(30)))!!.arguments
        assertNull(applyConfiguredEditorInsertion(editorInsertionContext("project", "main", editor), "project", "main",
            editor, contract, args, EditorInsertPosition.ABOVE, null, emptySet(), true))
        assertEquals(args, editor.nodeArguments(editor.selectedNodeId!!))
        val inserted = editor.currentSource
        assertTrue(editor.undo()); assertEquals(original, editor.currentSource)
        assertTrue(editor.redo()); assertEquals(inserted, editor.currentSource)
    }

    @Test fun elseIfAppendsCompletedConditionWithChildrenAndSingleUndo() {
        val editor = editor(); val original = editor.currentSource; val owner = editor.selectedNodeId!!
        val originalArguments = editor.nodeArguments(owner)!!.deepCopy()
        val originalSlots = editor.childBlockNames(owner)
        assertNull(append(editor, editorInsertionContext("project", "main", editor)))
        assertEquals(comparison(20), editor.nodeArguments(owner)!!.getAsJsonArray("elseIf")[0])
        assertEquals(originalSlots + "elseIf0", editor.childBlockNames(owner))
        assertEquals(owner, editor.selectedNodeId)
        val inserted = editor.currentSource
        assertTrue(editor.undo()); assertEquals(original, editor.currentSource)
        assertEquals(originalArguments, editor.nodeArguments(owner))
        assertTrue(editor.redo()); assertEquals(inserted, editor.currentSource)
    }

    @Test fun staleContextOrBusyRuntimeCannotAppendToAnotherTarget() {
        val editor = editor(); val context = editorInsertionContext("project", "main", editor)
        assertNotNull(append(editor, context, project = "other"))
        assertNotNull(append(editor, context, flow = "other"))
        assertNotNull(append(editor, context, mutable = false))
        assertEquals(context.source, editor.currentSource)
        editor.insertBlock(contract, comparison(40))
        val changed = editor.currentSource
        assertNotNull(append(editor, context))
        assertEquals(changed, editor.currentSource)
        editor.selectNodes(editor.rows.map { it.nodeId })
        assertNotNull(append(editor, editorInsertionContext("project", "main", editor).copy(selection = emptySet())))
        assertEquals(changed, editor.currentSource)
    }

    @Test fun wrongTargetAndBranchLimitLeaveExistingSourceUntouched() {
        val editor = editor()
        repeat(32) { index -> assertNull(append(editor, editorInsertionContext("project", "main", editor), comparison(index))) }
        val full = editor.currentSource
        assertNotNull(append(editor, editorInsertionContext("project", "main", editor)))
        assertEquals(full, editor.currentSource)
        editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")), JsonObject())
        val wrong = editor.currentSource
        assertNotNull(append(editor, editorInsertionContext("project", "main", editor)))
        assertEquals(wrong, editor.currentSource)
    }
}
