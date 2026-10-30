package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AiProposalTest {
    private val kinds = setOf("task.sleep", "control.repeat", "control.if", "variable.set")
    private fun proposal(body: String, mode: AiMode = AiMode.GENERATE) = parseAiProposal(body, mode, kinds)
    private fun sleep(ms: Int = 500) = """{"kind":"task.sleep","args":{"milliseconds":$ms}}"""
    private fun response(blocks: String) = """{"message":"方案","blocks":[$blocks]}"""
    private fun original(): VisualEditorState = VisualEditorState.create("", "block-main").apply {
        check(insertBlocks(listOf(requireNotNull(BlockCatalog.find("task.sleep")) to JsonObject().apply { addProperty("milliseconds", 100) }), EditorInsertPosition.BELOW))
    }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }

    @Test fun acceptsStructuredReplyAndExactScalarTypes() {
        val parsed = proposal(response("""{"kind":"variable.set","args":{"name":"文本","value":"+190"}}"""))
        assertEquals("+190", parsed.blocks.single().args["value"].asString)
        assertTrue(parsed.blocks.single().args["value"].asJsonPrimitive.isString)
    }
    @Test fun acceptsOneJsonFenceButRejectsSurroundingProse() {
        assertEquals(1, proposal("```json\n${response(sleep())}\n```").blocks.size)
        rejects { proposal("Here is the plan: ${response(sleep())}") }
    }
    @Test fun rejectsUnknownFunctionsAndBranchNames() {
        rejects { proposal(response("""{"kind":"shell.exec","args":{}}""")) }
        rejects { proposal(response("""{"kind":"task.sleep","args":{"milliseconds":1},"children":{"body":[]}}""")) }
    }
    @Test fun rejectsLegacyAndStructuralIdentityInjection() {
        rejects { parseAiProposal(response("""{"kind":"legacy.tap","args":{}}"""), AiMode.GENERATE, setOf("legacy.tap")) }
        rejects { proposal(response("""{"kind":"task.sleep","args":{},"nodeId":"node-1"}""")) }
    }
    @Test fun rejectsDuplicateFieldsAndDeepJsonBeforeRecursiveParsing() {
        rejects { proposal("""{"message":"first","message":"second","blocks":[]}""") }
        rejects { aiJsonObject("{\"a\":" + "[".repeat(60) + "0" + "]".repeat(60) + "}") }
        rejects { aiJsonObject("{message:'not strict json'}") }
    }
    @Test fun capsNodeCountAndNestedBranches() {
        rejects { proposal(response((0..256).joinToString(",") { sleep() })) }
        var block = sleep()
        repeat(18) { block = """{"kind":"control.repeat","args":{"times":2},"children":{"body":[$block]}}""" }
        rejects { proposal(response(block)) }
    }
    @Test fun explainAndDiagnoseRejectAllChanges() {
        for (mode in listOf(AiMode.EXPLAIN, AiMode.DIAGNOSE)) {
            rejects { proposal(response(sleep()), mode) }
            rejects { proposal("""{"message":"删除","replaceSelection":true}""", mode) }
            assertFalse(proposal("""{"message":"这是一段等待。"}""", mode).hasChanges)
        }
    }
    @Test fun generateCannotUpdateOrDeleteAndUpdatesCannotMixWithReplacement() {
        rejects { proposal("""{"message":"修改","updates":[{"nodeId":"node-1","args":{}}]}""") }
        rejects { proposal("""{"message":"删除","replaceSelection":true}""") }
        rejects { proposal("""{"message":"修改","replaceSelection":true,"updates":[{"nodeId":"node-1","args":{}}]}""", AiMode.MODIFY) }
    }
    @Test fun missingResourcesBlockPreviewWithoutMutatingLiveSource() {
        val editor = original(); val source = editor.currentSource
        val parsed = proposal("""{"message":"缺少结果变量","missingResources":["声明整数变量：结果"],"blocks":[${sleep()}]}""")
        rejects { aiCandidate(source, "block-main", emptySet(), null, parsed, AiMode.GENERATE, AiInsertPlace.END, null, VisualProjectIds()) }
        assertEquals(source, editor.currentSource)
    }
    @Test fun insertionPreviewIsDetachedAndUsesProjectWideHighWaterMarks() {
        val editor = original(); val source = editor.currentSource
        val ids = VisualProjectIds(listOf(source), listOf("block-main")).apply { reserveIds(listOf("node-383", "block-100")) }
        val parsed = proposal(response("""{"kind":"control.repeat","args":{"times":2},"children":{"body":[${sleep()}]}}"""))
        val candidate = aiCandidate(source, "block-main", emptySet(), null, parsed, AiMode.GENERATE, AiInsertPlace.END, null, ids)
        assertEquals(source, editor.currentSource); assertEquals(2, candidate.added)
        val rows = parseVisualNodes(candidate.source, "block-main")
        assertEquals(3, rows.size); assertTrue(rows.drop(1).all { it.nodeId.removePrefix("node-").toInt() > 383 })
        assertEquals(1, rows.last().depth)
    }
    @Test fun beforeAndAfterInsertAtTheChosenAnchor() {
        val editor = original(); val node = editor.rows.single().nodeId
        for ((place, first) in listOf(AiInsertPlace.BEFORE to 500, AiInsertPlace.AFTER to 100)) {
            val result = aiCandidate(editor.currentSource, "block-main", setOf(node), node, proposal(response(sleep())), AiMode.GENERATE, place, null, VisualProjectIds())
            val scratch = VisualEditorState.create(result.source, "block-main")
            assertEquals(first, scratch.nodeArguments(scratch.rows.first().nodeId)?.get("milliseconds")?.asInt)
        }
    }
    @Test fun insideTargetsTheExplicitBranch() {
        val editor = VisualEditorState.create("", "block-main")
        assertTrue(editor.insertBlocks(listOf(requireNotNull(BlockCatalog.find("control.repeat")) to JsonObject().apply { addProperty("times", 2) }), EditorInsertPosition.BELOW))
        val owner = editor.rows.single().nodeId
        val result = aiCandidate(editor.currentSource, "block-main", setOf(owner), owner, proposal(response(sleep())), AiMode.GENERATE, AiInsertPlace.INSIDE, "body", VisualProjectIds())
        assertEquals("body", parseVisualNodes(result.source, "block-main").last().childSlot)
    }
    @Test fun updatesKeepIdentityAndCannotEscapeTheSelectedSubtree() {
        val editor = original(); val node = editor.rows.single().nodeId
        val reply = proposal("""{"message":"改成1秒","updates":[{"nodeId":"$node","args":{"milliseconds":1000}}]}""", AiMode.MODIFY)
        val result = aiCandidate(editor.currentSource, "block-main", setOf(node), node, reply, AiMode.MODIFY, AiInsertPlace.END, null, VisualProjectIds())
        assertEquals(node, parseVisualNodes(result.source, "block-main").single().nodeId)
        assertEquals(1, result.changed); assertEquals(0, result.added)
        val foreign = proposal("""{"message":"改参","updates":[{"nodeId":"node-999","args":{"milliseconds":1000}}]}""", AiMode.MODIFY)
        rejects { aiCandidate(editor.currentSource, "block-main", setOf(node), node, foreign, AiMode.MODIFY, AiInsertPlace.END, null, VisualProjectIds()) }
    }
    @Test fun replacementAndDeletionCommitAsOneUndoableDraftChange() {
        val editor = original(); val node = editor.rows.single().nodeId; val before = editor.currentSource
        val reply = proposal("""{"message":"替换","replaceSelection":true,"blocks":[${sleep(1)},${sleep(2)}]}""", AiMode.MODIFY)
        val candidate = aiCandidate(before, "block-main", setOf(node), node, reply, AiMode.MODIFY, AiInsertPlace.END, null, VisualProjectIds())
        assertEquals(1, candidate.removed); assertEquals(2, candidate.added)
        assertNull(EditorProjectEdits().apply(mapOf("main" to before), mapOf("main" to candidate.source), mapOf("main" to editor)))
        assertTrue(editor.undo()); assertEquals(before, editor.currentSource)
        assertTrue(editor.redo()); assertEquals(candidate.source, editor.currentSource)
        val deleted = proposal("""{"message":"删除所选","replaceSelection":true}""", AiMode.MODIFY)
        val removal = aiCandidate(before, "block-main", setOf(node), node, deleted, AiMode.MODIFY, AiInsertPlace.END, null, VisualProjectIds())
        assertEquals("", removal.source)
    }
    @Test fun nonContiguousReplacementLeavesTheTreeAndHistoryAlone() {
        val editor = original()
        repeat(2) { check(editor.insertBlocks(listOf(requireNotNull(BlockCatalog.find("task.sleep")) to JsonObject().apply { addProperty("milliseconds", 1) }), EditorInsertPosition.BELOW)) }
        val before = editor.currentSource; val rows = editor.rows
        editor.selectNodes(setOf(rows[0].nodeId, rows[2].nodeId))
        assertFalse(editor.replaceSelectionWith(aiClipboards(proposal(response(sleep())).blocks)))
        assertEquals(before, editor.currentSource)
        assertEquals(setOf(rows[0].nodeId, rows[2].nodeId), editor.selectedNodeIds)
    }
    @Test fun staleApplyDoesNotOverwriteNewerDraft() {
        val editor = original(); val before = editor.currentSource
        editor.updateArguments(editor.rows.single().nodeId, JsonParser.parseString("{\"milliseconds\":200}").asJsonObject)
        val current = editor.currentSource
        assertNotNull(EditorProjectEdits().apply(mapOf("main" to before), mapOf("main" to ""), mapOf("main" to editor)))
        assertEquals(current, editor.currentSource)
    }
}
